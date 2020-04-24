/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jena.tdb2.sys;

import static org.junit.Assert.*;

import org.apache.commons.io.FileUtils;
import org.apache.jena.atlas.lib.FileOps;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.sse.SSE;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.ConfigTest;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.store.DatasetGraphSwitchable;
import org.apache.jena.tdb2.store.DatasetGraphTDB;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestDatabaseOps
{
    private Location dir = null;

    static Quad quad1 = SSE.parseQuad("(_ <s> <p> 1)");
    static Quad quad2 = SSE.parseQuad("(_ _:a <p> 2)");
    static Triple triple1 = quad1.asTriple();
    static Triple triple2 = quad2.asTriple();
    static Triple triple3 = SSE.parseTriple("(<s> <q> 3)");

    @Before
    public void before() {
        String DIR = ConfigTest.getCleanDir();
        FileOps.ensureDir(DIR);
        FileOps.clearAll(DIR);
        dir = Location.create(DIR);
    }

    @After
    public void after() {
        TDBInternal.reset();
        FileUtils.deleteQuietly(IOX.asFile(dir));
    }

    @Test public void compact_dsg_1() {
        DatasetGraph dsg = DatabaseMgr.connectDatasetGraph(dir);
        DatasetGraphSwitchable dsgs = (DatasetGraphSwitchable)dsg;
        DatasetGraph dsg1 = dsgs.get();
        Location loc1 = ((DatasetGraphTDB)dsg1).getLocation();

        Txn.executeWrite(dsg, ()-> {
            dsg.add(quad2);
            dsg.add(quad1);
        });
        DatabaseMgr.compact(dsg);

        assertFalse(StoreConnection.isSetup(loc1));

        DatasetGraph dsg2 = dsgs.get();
        Location loc2 = ((DatasetGraphTDB)dsg2).getLocation();

        assertNotEquals(dsg1, dsg2);
        assertNotEquals(loc1, loc2);

        Txn.executeRead(dsg, ()-> {
            assertTrue(dsg.contains(quad2));
            assertTrue(dsg.contains(quad1));
        });

        // dsg1 was closed and expelled. We must carefully reopen its storage only.
        DatasetGraph dsgOld = StoreConnection.connectCreate(loc1).getDatasetGraph();

        Txn.executeWrite(dsgOld, ()->dsgOld.delete(quad2));
        Txn.executeRead(dsg,     ()->assertTrue(dsg.contains(quad2)) );
        Txn.executeRead(dsg2,    ()->assertTrue(dsg2.contains(quad2)) );
    }

    @Test public void compact_graph_2() {
        // graphs across compaction.
        DatasetGraph dsg = DatabaseMgr.connectDatasetGraph(dir);
        Graph g = dsg.getDefaultGraph();

        DatasetGraphSwitchable dsgs = (DatasetGraphSwitchable)dsg;
        DatasetGraph dsg1 = dsgs.get();
        Location loc1 = ((DatasetGraphTDB)dsg1).getLocation();

        Txn.executeWrite(dsg, ()-> {
            dsg.add(quad2);
            dsg.add(quad1);
        });
        DatabaseMgr.compact(dsg);
        Txn.executeRead(dsg, ()-> {
            assertEquals(2, g.size());
            assertTrue(g.contains(triple2));
        });

        // Check is not attached to the old graph.
        DatasetGraph dsgOld = StoreConnection.connectCreate(loc1).getDatasetGraph();

        Txn.executeWrite(dsgOld, ()->dsgOld.getDefaultGraph().delete(triple2));
        Txn.executeRead(dsg,     ()->assertTrue(g.contains(triple2)) );

        Txn.executeWrite(dsg,    ()->g.add(triple3));
        Txn.executeRead(dsgOld,  ()->assertFalse(dsgOld.getDefaultGraph().contains(triple3)) );
    }

    @Test public void compact_prefixes_3() {
        // This test fails sometimes with an NPE at the marked line when run with Java14 on ASF Jenkins.
        // It does not failure anywhere else.
        // It does not always fail
        // There isn't a pointer deference to NPE!
        //java.lang.NullPointerException
        //        at org.apache.jena.tdb2.sys.TestDatabaseOps.compact_prefixes_3(TestDatabaseOps.java:142)
        // No more stack
        // Attempt to find out what is going on:
        try { 
            compact_prefixes_3_test();
        } catch (Throwable th) {
            th.printStackTrace();
            throw th;
        }
    }
    
    private void compact_prefixes_3_test() {
        // prefixes across compaction.
        DatasetGraph dsg = DatabaseMgr.connectDatasetGraph(dir);
        Graph g = dsg.getDefaultGraph();
        Txn.executeWrite(dsg, ()-> g.getPrefixMapping().setNsPrefix("ex", "http://example/") );

        Txn.executeRead(dsg, ()-> {
            assertEquals("ex", g.getPrefixMapping().getNsURIPrefix("http://example/"));
            assertEquals("http://example/", g.getPrefixMapping().getNsPrefixURI("ex"));
        });

        DatasetGraphSwitchable dsgs = (DatasetGraphSwitchable)dsg;
        DatasetGraph dsg1 = dsgs.get();
        Location loc1 = ((DatasetGraphTDB)dsg1).getLocation();

        DatabaseMgr.compact(dsgs); // HERE : Line 142 before investigation code added.

        Graph g2 = dsgs.getDefaultGraph();

        Txn.executeRead(dsgs, ()-> {
            assertEquals("ex", g2.getPrefixMapping().getNsURIPrefix("http://example/"));
            assertEquals("http://example/", g2.getPrefixMapping().getNsPrefixURI("ex"));
        });

        // Check is not attached to the old graph.
        DatasetGraph dsgOld = StoreConnection.connectCreate(loc1).getDatasetGraph();

        Txn.executeWrite(dsgOld, ()->dsgOld.getDefaultGraph().getPrefixMapping().removeNsPrefix("ex"));
        Txn.executeRead(dsg,     ()->assertEquals("http://example/", g.getPrefixMapping().getNsPrefixURI("ex")));

        Txn.executeWrite(dsg,    ()->g.getPrefixMapping().setNsPrefix("ex2", "http://exampl2/") );
        Txn.executeRead(dsgOld,  ()->assertNull(dsgOld.getDefaultGraph().getPrefixMapping().getNsPrefixURI("ex")));
    }

    @Test public void backup_1() {
        DatasetGraph dsg = DatabaseMgr.connectDatasetGraph(dir);
        Txn.executeWrite(dsg, ()-> {
            dsg.add(quad2);
            dsg.add(quad1);
        });
        String file1 = DatabaseMgr.backup(dsg);
        DatasetGraph dsg2 = RDFDataMgr.loadDatasetGraph(file1);
        Txn.executeRead(dsg, ()-> {
            assertTrue(dsg.contains(quad1));
            assertEquals(2, dsg.getDefaultGraph().size());
            assertTrue(dsg2.getDefaultGraph().isIsomorphicWith(dsg.getDefaultGraph()));
        });
        String file2 = DatabaseMgr.backup(dsg);
        assertNotEquals(file1, file2);
    }

}
