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

package com.ncc.neon.query.elasticsearch
import com.ncc.neon.connect.ConnectionManager
import com.ncc.neon.connect.NeonConnectionException
import com.ncc.neon.query.Query
import com.ncc.neon.query.QueryOptions
import com.ncc.neon.query.clauses.GroupByFieldClause
import com.ncc.neon.query.clauses.GroupByFunctionClause
import com.ncc.neon.query.clauses.LimitClause
import com.ncc.neon.query.clauses.WhereClause
import com.ncc.neon.query.executor.AbstractQueryExecutor
import com.ncc.neon.query.filter.Filter
import com.ncc.neon.query.filter.FilterState
import com.ncc.neon.query.filter.SelectionState
import com.ncc.neon.query.result.ArrayCountPair
import com.ncc.neon.query.result.QueryResult
import com.ncc.neon.query.result.TabularQueryResult
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.search.SearchType
import org.elasticsearch.client.Client
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.search.aggregations.AggregationBuilders
import org.elasticsearch.search.aggregations.bucket.MultiBucketsAggregation
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class ElasticSearchQueryExecutor extends AbstractQueryExecutor {

    static final String STATS_AGG_PREFIX = "_statsFor_"

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticSearchQueryExecutor)

    @Autowired
    private FilterState filterState

    @Autowired
    private SelectionState selectionState

    @Autowired
    private ConnectionManager connectionManager

    @Override
    QueryResult doExecute(Query query, QueryOptions options) {
        long d1 = new Date().getTime()

        ElasticSearchConversionStrategy conversionStrategy = new ElasticSearchConversionStrategy(filterState: filterState, selectionState: selectionState)
        def request = conversionStrategy.convertQuery(query, options)

        def aggregates = query.aggregates
        def groupByClauses = query.groupByClauses

        def results = getClient().search(request).actionGet()
        def aggResults = results.aggregations

        def returnVal
        if (aggregates && !groupByClauses) {
            returnVal = new TabularQueryResult([
                extractMetrics(aggregates, aggResults ? aggResults.asMap() : null, results.hits.totalHits)
            ])
        } else if (groupByClauses) {
            List<Map<String, Object>> buckets = extractBuckets(groupByClauses, aggResults.asList()[0], aggregates)
            buckets = limitBuckets(buckets, query)
            returnVal = new TabularQueryResult(buckets)
        } else if(query.isDistinct) {
            returnVal = new TabularQueryResult(extractDistinct(query, aggResults.asList()[0]))
        }
        else {
            returnVal = new TabularQueryResult(results.hits.collect { it.getSource() })
        }

        long diffTime = new Date().getTime() - d1
        LOGGER.debug(" Query took: " + diffTime + " ms ")

        return returnVal
    }

    List<Map<String, Object>> extractDistinct(Query query, aggResult) {
        String field = query.fields[0]

        def distinctValues = []
        aggResult.buckets.each {
            def accumulator = [:]
            accumulator[field] = it.key
            distinctValues.push(accumulator)
        }

        distinctValues = sortDistinct(query, distinctValues)

        int offset = ElasticSearchConversionStrategy.getOffset(query)
        int limit = ElasticSearchConversionStrategy.getLimit(query, true)

        if(limit == 0) {
            limit = distinctValues.size()
        }

        int endIndex = ((limit - 1) + offset) < (distinctValues.size() - 1) ? ((limit - 1) + offset) : (distinctValues.size() - 1)
        endIndex = (endIndex > offset ? endIndex : offset)
        distinctValues = (offset >= distinctValues.size()) ? [] : distinctValues[offset..endIndex]

        return distinctValues
    }

    private List<Map<String, Object>> sortDistinct(Query query, values) {
        if(query.sortClauses) {
            return values.sort { a, b ->
                a[query.fields[0]] <=> b[query.fields[0]]
            }
        }

        return values
    }

    @Override
    List<String> showDatabases() {
        LOGGER.debug("Executing showDatabases to retrieve indices")
        getClient().admin().cluster().state(new ClusterStateRequest()).actionGet().state.metaData.indices.collect { it.key }
    }

    @Override
    List<String> showTables(String dbName) {
        LOGGER.debug("Executing showTables for index " + dbName + " to get type mappings")
        // Elastic search allows wildcards in index names.  If dbName contains a wildcard, find all matches.
        if (dbName?.contains('*')) {
            def match = dbName.replaceAll(/\*/, '.*')
            List<String> tables = []
            def mappings = getMappings()
            mappings.keysIt().each {
                if (it.matches(match)) {
                    tables.addAll(mappings.get(it).collect{ table -> table.key })
                }
            }
            return tables.unique()
        }
        // Fall through case is to return the exact match.
        getMappings().get(dbName).collect { it.key }
    }

    @Override
    List<String> getFieldNames(String databaseName, String tableName) {
        if(tableName) {
            LOGGER.debug("Executing getFieldNames for index " + databaseName + " type " + tableName)

            def dbMatch = databaseName.replaceAll(/\*/, '.*')
            def tableMatch = tableName.replaceAll(/\*/, '.*')

            def fields = []
            def mappings = getMappings()
            mappings.keysIt().each { dbKey ->
                if (dbKey.matches(dbMatch)) {
                    def dbMappings = mappings.get(dbKey)
                    dbMappings.keysIt().each { tableKey ->
                        if (tableKey.matches(tableMatch)) {
                            def tableMappings = dbMappings.get(tableKey)
                            fields.addAll(getFieldsInObject(tableMappings.getSourceAsMap(), null))
                        }
                    }
                }
            }

            if (fields) {
                fields.add("_id")
                return fields.unique()
            }
        }
        return []
    }

    @Override
   Map getFieldTypes(String databaseName, String tableName) {
       if(tableName) {
           LOGGER.debug("Executing getFieldTypes for index " + databaseName + " type " + tableName)

           def dbMappings = getMappings().get(databaseName)
           if(dbMappings) {
               def tableMappings = dbMappings.get(tableName)
               if(tableMappings) {
                   return getFieldTypesInObject(tableMappings.getSourceAsMap(), null)
               }
           }
       }
       return [:]
   }

    List<ArrayCountPair> getArrayCounts(String databaseName, String tableName, String field, int limit = 0, WhereClause whereClause = null) {
        Query query = new Query(filter: new Filter(databaseName: databaseName, tableName: tableName),
                    limitClause: new LimitClause(limit: 0))
        ElasticSearchConversionStrategy conversionStrategy = new ElasticSearchConversionStrategy(filterState: filterState, selectionState: selectionState)
        SearchRequest searchRequest = ElasticSearchConversionStrategy.createSearchRequest(
                conversionStrategy.createSourceBuilderWithState(query, QueryOptions.DEFAULT_OPTIONS, whereClause)
                        .aggregation(AggregationBuilders.terms("arrayCount")
                        .field(field)
                        .size(limit)
                ) , query)
                .searchType(SearchType.COUNT)
        def results = getClient()
            .search(searchRequest)
            .actionGet()

        def aggResults = results.aggregations
        return aggResults
            .asList()
            .head()
            .getBuckets()
            .collect { new ArrayCountPair(key: it.key, count: it.docCount) }
    }

    private Client getClient() {
        return connectionManager.connection.client
    }

    private ImmutableOpenMap getMappings() {
        getClient().admin().indices().getMappings(new GetMappingsRequest()).actionGet().mappings
    }

    private static Map<String, Object> extractMetrics(clauses, results, totalCount) {
        def (countAllClause, metricClauses) = clauses.split {
            ElasticSearchConversionStrategy.isCountAllAggregation(it) || ElasticSearchConversionStrategy.isCountFieldAggregation(it)
        }

        def metrics = metricClauses.inject([:]) { acc, clause ->
            def result = results.get("${STATS_AGG_PREFIX}${clause.field}" as String)
            acc.put(clause.name, result[clause.operation])
            acc
        }

        if (countAllClause) {
            metrics.put(countAllClause[0].name, totalCount)
        }
        metrics
    }

    /*
     * The aggregation results from ES will be a tree of aggregation -> buckets -> aggregation -> buckets -> etc
     * we want to flatten it into a list of buckets where we have one item in the list for each leaf in the tree.
     * Each item is an accumulation of all the buckets along the path to the leaf.
     *
     * We process an aggregation by getting the list of buckets and calling extract again for each of them.
     * For each bucket we copy the current accumulator, since we're branching into more paths in the tree.
     *
     * We process a bucket by getting the current groupByClause and using it to get the value we're interested in
     * from the bucket. Then if there are more groupByClauses, we recurse into extract using the rest of the clauses
     * and the nested aggregation. If there are no more clauses to process, then we've reached the bottom of the tree
     * we add any metric aggregations to the bucket and push it onto the result list.
     */
    private static List<Map<String, Object>> extractBuckets(groupByClauses, value, metricAggs, Map accumulator = [:], List<Map<String, Object>> results = []) {
        value.buckets.each {
            def newAccumulator = [:]
            newAccumulator.putAll(accumulator)
            extractBucket(groupByClauses, it, metricAggs, newAccumulator, results)
        }

        return results
    }

    private static void extractBucket(groupByClauses, value, metricAggs, Map accumulator, List<Map<String, Object>> results) {
        def currentClause = groupByClauses.head()
        switch (currentClause.getClass()) {
            case GroupByFieldClause:
                accumulator.put(currentClause.field, value.key)
                break
            case GroupByFunctionClause:
                def isDateClause = (currentClause.operation in ElasticSearchConversionStrategy.DATE_OPERATIONS
                        && value.key.isNumber())

                accumulator.put(groupByClauses.head().name, isDateClause ? value.key.toFloat() : value.key)
                break
            default:
                throw new NeonConnectionException("Bad implementation - ${currentClause.getClass()} is not a valid groupByClause")
        }

        if (groupByClauses.tail()) {
            extractBuckets(groupByClauses.tail(), (MultiBucketsAggregation)value.getAggregations().asList().head(), metricAggs, accumulator, results)
        } else {
            def terminalAggs = value.getAggregations()
            if (terminalAggs) {
                accumulator.putAll(extractMetrics(metricAggs, terminalAggs.asMap(), value.docCount))
            }
            results.push(accumulator)
        }
    }

    private List<Map<String, Object>> limitBuckets(def buckets, Query query) {
        int offset = ElasticSearchConversionStrategy.getOffset(query)
        int limit = ElasticSearchConversionStrategy.getLimit(query, true)

        if(limit == 0) {
            limit = buckets.size()
        }

        int endIndex = ((limit - 1) + offset) < (buckets.size() - 1) ? ((limit - 1) + offset) : (buckets.size() - 1)
        endIndex = (endIndex > offset ? endIndex : offset)

        def result = (offset >= buckets.size()) ? [] : buckets[offset..endIndex]

        return result
    }

    private Map getFieldTypesInObject(Map fields, String parentFieldName) {
        def fieldsToTypes = [:]
        fields.get('properties').each { field ->
            String fieldName = field.getKey()
            if(parentFieldName) {
                fieldName = parentFieldName + "." + field.getKey()
            }
            if(field.getValue().containsKey('type')) {
                fieldsToTypes.put(fieldName, field.getValue().get('type'))
            } else {
                fieldsToTypes.putAll(getFieldTypesInObject(field.getValue(), fieldName))
            }
        }
        return fieldsToTypes
    }

    private List<String> getFieldsInObject(Map fields, String parentFieldName) {
        def fieldNames = []
        fields.get('properties').each { field ->
            def type = field.getValue().containsKey('type') ? field.getValue().get('type') : 'object'

            String fieldName = field.getKey()
            if(parentFieldName) {
                fieldName = parentFieldName + "." + field.getKey()
            }

            if(type == 'object') {
                fieldNames.addAll(getFieldsInObject(field.getValue(), fieldName))
            } else {
                fieldNames.add(fieldName)
            }
        }

        return fieldNames
    }
}
