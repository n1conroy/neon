/*
 * Copyright 2016 Next Century Corporation
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

package com.ncc.neon.sse

import com.ncc.neon.query.Query
import com.ncc.neon.query.result.QueryResult

/**
 * Stores a Neon query object, as well as a variety of information about it that is relevant to it and its current
 * state.
 *
 * An example of a sseQueryData would be from a 'select count(*) from table group by day', to get the counts.
 * The data would be active=true, complete=false, randMin=0.001, randMax=0.002, and something like the following
 * for the underlying query result data:
 *      [  day 1: 234,
 *         day 2: 5324,
 *         day 3, 345
 *      ]
 *  This would result in 3 runningResults, mapping [ day1 : singlepointstats for day 1 ] etc. where the
 *  SinglePointStats keep track of the mean, variance, etc.
 */
class SseQueryData {

    // -----------------------------
    // Statistics about the entire set (these do not change over iterations)
    // -----------------------------

    // Whether or not the query is still actively being processed.
    boolean active

    // Whether or not the query was completed (for use if/when result caching is implemented).
    boolean complete

    // Number of records in the collection the query is acting on.
    long totalRecordCount

    // Host of the database the query was executed on, so that when comparing identical queries on different databases they can be distinguished.
    String host

    // Type of database the query was executed on, so that identically-named databases on different data stores can be distinguished.
    String databaseType

    // Extra information associated with the query.
    boolean ignoreFilters
    boolean selectionOnly
    Set<String> ignoredFilterIds

    // z(p) value associated with the desired confidence of this query.
    double zp

    // The name of the field in which the random value for the table to be searched is stored.
    String randomField

    // Query this data is associated with.
    Query query

    // -----------------------------
    // Statistics about where we are now
    // -----------------------------

    // Number of iterations that we expect the query to take
    double iterationsToDate

    // Current minimum value of the random value field in the collection the query is acting on.
    double randMin

    // Current maximum  value of the random value field in the collection the query is acting on.
    double randMax

    // Current step value of the random value field in the collection the query is acting on.
    double randStep

    // Number of records that have been searched to date
    long recordsToDate

    // How long the last query took
    long duration

    // The results of the last Query
    QueryResult queryResult

    // Statistical results (or results so far, if incomplete) of the query. Each  key is an ID generated by makeId() in SseQueryService,
    // and each value should be a com.ncc.neon.sse.SinglePointStats object.
    Map<Object, SinglePointStats> runningResults = [:]

    // All the results from all the runningResults. Should be the same as totalRecordCount at end
    long grandTotal
}