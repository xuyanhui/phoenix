/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *     Neither the name of Salesforce.com nor the names of its contributors may 
 *     be used to endorse or promote products derived from this software without 
 *     specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE 
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, 
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.phoenix.execute;


import java.sql.SQLException;
import java.util.List;

import com.salesforce.phoenix.compile.GroupByCompiler.GroupBy;
import com.salesforce.phoenix.compile.OrderByCompiler.OrderBy;
import com.salesforce.phoenix.compile.*;
import com.salesforce.phoenix.coprocessor.ScanRegionObserver;
import com.salesforce.phoenix.iterate.*;
import com.salesforce.phoenix.query.*;
import com.salesforce.phoenix.schema.TableRef;



/**
 * 
 * Query plan for a basic table scan
 *
 * @author jtaylor
 * @since 0.1
 */
public class ScanPlan extends BasicQueryPlan {
    private List<KeyRange> splits;
    
    public ScanPlan(StatementContext context, TableRef table, RowProjector projection, Integer limit, OrderBy orderBy) {
        super(context, table, projection, context.getBindManager().getParameterMetaData(), limit, orderBy);
        if (limit != null && !orderBy.getOrderByExpressions().isEmpty()) { // TopN
            ScanRegionObserver.serializeIntoScan(context.getScan(), limit, orderBy.getOrderByExpressions());
        }
    }
    
    @Override
    public List<KeyRange> getSplits() {
        return splits;
    }
    
    @Override
    public boolean isAggregate() {
        return false;
    }
    
    @Override
    protected Scanner newScanner(ConnectionQueryServices services) throws SQLException {
        // Set any scan attributes before creating the scanner, as it will be too late afterwards
        context.getScan().setAttribute(ScanRegionObserver.NON_AGGREGATE_QUERY, QueryConstants.TRUE);
        ResultIterator scanner;
        /* If no limit or topN, use parallel iterator so that we get results faster. Otherwise, if
         * limit is provided, run query serially.
         */
        if (limit == null || !orderBy.getOrderByExpressions().isEmpty()) {
            ParallelIterators iterators = new ParallelIterators(context, table, RowCounter.UNLIMIT_ROW_COUNTER, GroupBy.EMPTY_GROUP_BY);
            splits = iterators.getSplits();
            if (orderBy.getOrderByExpressions().isEmpty()) {
                scanner = new ConcatResultIterator(iterators);
            } else {
                scanner = new MergeSortTopNResultIterator(iterators, limit, orderBy.getOrderByExpressions());
            }
        } else {
            scanner = new TableResultIterator(context, table);
            scanner = new SerialLimitingResultIterator(scanner, limit, new ScanRowCounter());
            splits = null;
        }

        return new WrappedScanner(scanner, getProjector());
    }
}
