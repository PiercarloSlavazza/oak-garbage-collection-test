/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.plugins.value.jcr;

import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.plugins.blob.BlobReferenceRetriever;
import org.apache.jackrabbit.oak.plugins.blob.ReferenceCollector;
import org.apache.jackrabbit.oak.segment.SegmentBlob;

import javax.jcr.*;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

@SuppressWarnings("ClassCanBeRecord")
public class BlobsJcrDataRetriever implements BlobReferenceRetriever {

    private final Supplier<Session> adminSessionSupplier;

    public BlobsJcrDataRetriever(Supplier<Session> adminSessionSupplier) {
        this.adminSessionSupplier = adminSessionSupplier;
    }

    public Set<BlobId> searchBlobs(Session session) {
        Set<BlobId> blobIds = new HashSet<>();
        try {
            QueryManager queryManager = session.getWorkspace().getQueryManager();

            String expression = "SELECT * FROM [nt:resource] AS node " +
                    "WHERE node.[jcr:data] IS NOT NULL AND node.[jcr:data] <> \"\"";
            Query query = queryManager.createQuery(expression, javax.jcr.query.Query.JCR_SQL2);
            QueryResult queryResult = query.execute();
            for (NodeIterator nodeIterator = queryResult.getNodes(); nodeIterator.hasNext(); ) {
                Node node = nodeIterator.nextNode();
                Property property = node.getProperty("jcr:data");
                Value value = property.getValue();
                if (!(value instanceof ValueImpl valueImpl)) throw new IllegalStateException();
                Blob blob = valueImpl.getBlob();
                if (!(blob instanceof SegmentBlob segmentBlob)) continue;
                String blobId = segmentBlob.getBlobId();
                blobIds.add(new BlobId(blobId));
            }
        } catch (RepositoryException e) {
            throw new RuntimeException(e);
        }
        return blobIds;
    }

    @Override
    public void collectReferences(final ReferenceCollector collector) {
        searchBlobs(adminSessionSupplier.get()).forEach(blobId -> collector.addReference(blobId.blobId(), null));
    }
}

