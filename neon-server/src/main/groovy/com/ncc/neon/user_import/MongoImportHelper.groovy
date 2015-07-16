/*
 * Copyright 2015 Next Century Corporation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ncc.neon.user_import

import org.apache.commons.io.LineIterator
import org.springframework.stereotype.Component

import com.mongodb.MongoClient
import com.mongodb.DB
import com.mongodb.DBCollection
import com.mongodb.DBCursor
import com.mongodb.DBObject
import com.mongodb.BasicDBObject

/**
 * Implements methods to add, remove, and convert fields of records in a mongo database.
 */
@Component
class MongoImportHelper implements ImportHelper {
    
    // TODO adapt this to accept some pretty name, even if only to store in the meta-information database.
    @Override
    List uploadData(String host, String identifier, LineIterator reader) {
        MongoClient mongo = new MongoClient(host, 27017)
        DB database = mongo.getDB(identifier)
        DBCollection collection = database.getCollection(ImportUtilities.MONGO_USERDATA_COLL_NAME)
        addMetadata(mongo, database.getName(), identifier)

        String row = ImportUtilities.getNextWholeRow(reader)
        if(!row || !reader.hasNext()) {
            return null
        }
        List fields = ImportUtilities.getCellsFromRow(row)

        row = ImportUtilities.getNextWholeRow(reader)
        while(row) {
            List values = ImportUtilities.getCellsFromRow(row)
            DBObject record = new BasicDBObject()
            for(int x = 0; x < values.size(); x++) {
                record.append(fields[x], values[x])
            }
            collection.insert(record)
            row = ImportUtilities.getNextWholeRow(reader)
        }
        List guesses = guessTypes(collection, fields)
        mongo.close()
        return guesses
    }

    @Override
    boolean dropData(String host, String identifier) {
        MongoClient mongo = new MongoClient(host, 27017)
        getDatabase(mongo, identifier).dropDatabase()
        DB metaDatabase = mongo.getDB(ImportUtilities.MONGO_META_DB_NAME)
        DBCollection metaCollection = metaDatabase.getCollection(ImportUtilities.MONGO_META_COLL_NAME)
        metaCollection.remove([databaseName: identifier] as BasicDBObject)
        if(!metaCollection.getCount()) {
            metaDatabase.dropDatabase()
        }
        mongo.close()
        return true
    }

    @Override
    List convertFields(String host, String identifier, UserFieldDataBundle bundle) {
        List<FieldTypePair> fields = bundle.fields, failedFields = [], tempFailed = []
        MongoClient mongo = new MongoClient(host, 27017)
        DBCollection collection = getDatabase(mongo, identifier).getCollection(ImportUtilities.MONGO_USERDATA_COLL_NAME)
        DBCursor cursor = collection.find()
        while(cursor.hasNext()) {
            BasicDBObject record = cursor.next() as BasicDBObject
            fields.each { field ->
                String s = record.get(field.name) as String
                if(s == "" || (field.type =~ /(?i)integer|long|double|float(?-i)/ && s =~ /(?i)none|null(?-i)/ && s.length() == 4)) {
                    record.removeField(field.name) // TODO - As it is this is pretty permanent. Given the file size restriction maybe this is okay because they can just re-upload?
                    return
                }
                Object result = ImportUtilities.convertValueToType(record.get(field.name), field.type, bundle.format)
                (result) ? record.put(field.name, result) : tempFailed.add(field)
            }
            failedFields.addAll(tempFailed)
            fields.removeAll(tempFailed)
            tempFailed.clear()
            collection.save(record)
        }
        cursor.close()
        mongo.close()
        return failedFields
    }

    private DB getDatabase(MongoClient mongo, String identifier) {
        DB metaDatabase = mongo.getDB(ImportUtilities.MONGO_META_DB_NAME)
        DBCollection metaCollection = metaDatabase.getCollection(ImportUtilities.MONGO_META_COLL_NAME)
        DBObject metaRecord = metaCollection.findOne([identifier: identifier] as BasicDBObject)
        return mongo.getDB(metaRecord.get("databaseName"))
    }

    private void addMetadata(MongoClient mongo, String databaseName, String identifier) {
        DB metaDatabase = mongo.getDB(ImportUtilities.MONGO_META_DB_NAME)
        DBCollection metaCollection = metaDatabase.getCollection(ImportUtilities.MONGO_META_COLL_NAME)
        DBObject metaRecord = new BasicDBObject()
        metaRecord.append("identifier", identifier)
        metaRecord.append("databaseName", databaseName)
        metaCollection.insert(metaRecord)
    }

    private List guessTypes(DBCollection collection, List fields) {
        long numRecords = (collection.count() > ImportUtilities.NUM_TYPE_CHECKED_RECORDS) ? ImportUtilities.NUM_TYPE_CHECKED_RECORDS : collection.count()
        List fieldsAndTypes = []
        List records = collection.find().limit(numRecords as int).toArray() // Safe so long as NUM_TYPE_CHECKED_RECORDS is reasonable.
        fields.each { field ->
            List valuesOfField = []
            records.each { record ->
                valuesOfField.add(record.get(field))
            }
            FieldTypePair pair = null
            // Sets the pair type equals to the first type that matches.
            pair = (!pair && ImportUtilities.isListIntegers(valuesOfField)) ? new FieldTypePair(name: field, type: "Integer") : pair
            pair = (!pair && ImportUtilities.isListLongs(valuesOfField)) ? new FieldTypePair(name: field, type: "Long") : pair
            pair = (!pair && ImportUtilities.isListDoubles(valuesOfField)) ? new FieldTypePair(name: field, type: "Double") : pair
            pair = (!pair && ImportUtilities.isListFloats(valuesOfField)) ? new FieldTypePair(name: field, type: "Float") : pair
            pair = (!pair && ImportUtilities.isListDates(valuesOfField)) ? new FieldTypePair(name: field, type: "Date") : pair
            pair = (!pair) ? new FieldTypePair(name: field, type: "String") : pair
            fieldsAndTypes.add(pair)
        }
        return fieldsAndTypes
    }
}