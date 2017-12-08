/**
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
package org.apache.metamodel.mongodb.mongo2;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mongodb.WriteConcern;
import org.apache.metamodel.DataContext;
import org.apache.metamodel.UpdateCallback;
import org.apache.metamodel.UpdateScript;
import org.apache.metamodel.data.DataSet;
import org.apache.metamodel.data.InMemoryDataSet;
import org.apache.metamodel.query.FunctionType;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.schema.Schema;
import org.apache.metamodel.schema.Table;
import org.apache.metamodel.util.SimpleTableDef;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.Mongo;

public class MongoDbDataContextTest extends MongoDbTestCase {

    private DB db;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (isConfigured()) {
            Mongo mongo = new Mongo(getHostname());
            db = mongo.getDB(getDatabaseName());
        }
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (isConfigured()) {
            db.dropDatabase();
        }
    }

    public void testNestedObjectFetching() throws Exception {
        if (!isConfigured()) {
            System.err.println(getInvalidConfigurationMessage());
            return;
        }

        DBCollection col = db.createCollection(getCollectionName(), new BasicDBObject());

        // delete if already exists
        {
            col.drop();
            col = db.createCollection(getCollectionName(), new BasicDBObject());
        }

        final BasicDBList list = new BasicDBList();
        list.add(new BasicDBObject().append("city", "Copenhagen").append("country", "Denmark"));
        list.add(new BasicDBObject().append("city", "Stockholm").append("country", "Sweden"));

        final BasicDBObject dbRow = new BasicDBObject();
        dbRow.append("name", new BasicDBObject().append("first", "John").append("last", "Doe"));
        dbRow.append("gender", "MALE");
        dbRow.append("addresses", list);
        col.insert(dbRow);

        final MongoDbDataContext dc = new MongoDbDataContext(db, new SimpleTableDef(getCollectionName(), new String[] {
                "name.first", "name.last", "gender", "addresses", "addresses[0].city", "addresses[0].country",
                "addresses[5].foobar" }));

        final DataSet ds = dc.query().from(getCollectionName()).selectAll().execute();
        try {
            assertTrue(ds.next());
            final Object addresses = ds.getRow().getValue(3);
            assertEquals("Row[values=[John, Doe, MALE, " + addresses + ", Copenhagen, Denmark, null]]", ds.getRow()
                    .toString());
            assertTrue(addresses instanceof List);
            assertFalse(ds.next());
        } finally {
            ds.close();
        }
    }

    public void testQueriesWithAutoGeneratedID() throws Exception {
        if (!isConfigured()) {
            System.err.println(getInvalidConfigurationMessage());
            return;
        }

        DBCollection col = db.createCollection(getCollectionName(), new BasicDBObject());

        // delete if already exists
        {
            col.drop();
            col = db.createCollection(getCollectionName(), new BasicDBObject());
        }

        // create a couple of entries

        BasicDBObject dbRow1 = new BasicDBObject();
        dbRow1.put("name", "Mr. Black");
        dbRow1.put("category", "gen_id");
        dbRow1.put("age", 20);
        col.insert(dbRow1, WriteConcern.ACKNOWLEDGED);
        final String autoGenID1 = dbRow1.get("_id").toString();

        BasicDBObject dbRow2 = new BasicDBObject();
        dbRow2.put("name", "Mr. Pink");
        dbRow2.put("category", "gen_id");
        dbRow2.put("age", 40);
        col.insert(dbRow2, WriteConcern.ACKNOWLEDGED);
        String autoGenID2 = dbRow2.get("_id").toString();

        BasicDBObject dbRow3 = new BasicDBObject();
        dbRow3.put("_id", "123");
        dbRow3.put("name", "Mr. White");
        dbRow3.put("category", "gen_id");
        dbRow3.put("age", 30);
        col.insert(dbRow3, WriteConcern.ACKNOWLEDGED);
        String fixedID3 = dbRow3.get("_id").toString();

        final MongoDbDataContext dc = new MongoDbDataContext(db);
        DataSet ds;

        // check all 3 entries inserted
        ds = dc.query().from(getCollectionName()).selectAll()
                .where("category").eq("gen_id").execute();
        assertEquals(3, ds.toRows().size());
        ds.close();

        // select by autogenerated id
        ds = dc.query().from(getCollectionName()).select("name").where("_id").eq(autoGenID1).execute();
        assertTrue(ds.next());
        assertEquals("Mr. Black", ds.getRow().getValue(0));
        ds.close();

        // select by multiple autogenerated ids
        ds = dc.query().from(getCollectionName()).select("name")
                .where("_id").eq(autoGenID1)
                .or("_id").eq(autoGenID2)
                .execute();
        assertEquals(2, ds.toRows().size());
        ds.close();

        // select by both autogenerated id and fixed id
        ds = dc.query().from(getCollectionName()).select("name")
                .where("_id").eq(autoGenID1)
                .or("_id").eq(fixedID3)
                .execute();
        assertEquals(2, ds.toRows().size());
        ds.close();

        // delete by id
        dc.executeUpdate(new UpdateScript() {
            @Override
            public void run(UpdateCallback callback) {
                callback.deleteFrom(getCollectionName())
                        .where("_id").eq(autoGenID1)
                        .execute();
            }
        });

        // select by autogenerated id which was deleted
        ds = dc.query().from(getCollectionName()).select("name").where("_id").eq(autoGenID1).execute();
        assertEquals(0, ds.toRows().size());
        ds.close();

    }

    public void testFirstRowAndMaxRows() throws Exception {
        if (!isConfigured()) {
            System.err.println(getInvalidConfigurationMessage());
            return;
        }

        DBCollection col = db.createCollection(getCollectionName(), new BasicDBObject());

        // delete if already exists
        {
            col.drop();
            col = db.createCollection(getCollectionName(), new BasicDBObject());
        }

        // create 3 records
        for (int i = 0; i < 3; i++) {
            BasicDBObject dbRow = new BasicDBObject();
            dbRow.put("id", i + 1);
            col.insert(dbRow);
        }

        final MongoDbDataContext dc = new MongoDbDataContext(db);

        DataSet ds;

        ds = dc.query().from(getCollectionName()).select("id").firstRow(2).execute();
        assertTrue(ds instanceof MongoDbDataSet);
        assertTrue(ds.next());
        assertEquals("Row[values=[2]]", ds.getRow().toString());
        assertTrue(ds.next());
        assertEquals("Row[values=[3]]", ds.getRow().toString());
        assertFalse(ds.next());
        ds.close();

        ds = dc.query().from(getCollectionName()).select("id").maxRows(1).execute();
        assertTrue(ds instanceof MongoDbDataSet);
        assertTrue(ds.next());
        assertEquals("Row[values=[1]]", ds.getRow().toString());
        assertFalse(ds.next());
        ds.close();

        ds = dc.query().from(getCollectionName()).select("id").maxRows(1).firstRow(2).execute();
        assertTrue(ds instanceof MongoDbDataSet);
        assertTrue(ds.next());
        assertEquals("Row[values=[2]]", ds.getRow().toString());
        assertFalse(ds.next());
        ds.close();
    }

    public void testRead() throws Exception {
        // Adding a comment to commit something and invoke a build in Travis...
        if (!isConfigured()) {
            System.err.println(getInvalidConfigurationMessage());
            return;
        }

        DBCollection col = db.createCollection(getCollectionName(), new BasicDBObject());

        // delete if already exists
        {
            col.drop();
            col = db.createCollection(getCollectionName(), new BasicDBObject());
        }

        // create 1000 records
        for (int i = 0; i < 1000; i++) {
            BasicDBObject dbRow = new BasicDBObject();
            dbRow.put("id", i);
            dbRow.put("name", "record no. " + i);
            if (i % 5 == 0) {
                dbRow.put("foo", "bar");
            } else {
                dbRow.put("foo", "baz");
            }
            BasicDBObject nestedObj = new BasicDBObject();
            nestedObj.put("count", i);
            nestedObj.put("constant", "foobarbaz");
            dbRow.put("baz", nestedObj);

            dbRow.put("list", Arrays.<Object> asList("l1", "l2", "l3", i));

            col.insert(dbRow);
        }

        // Instantiate the actual data context
        final DataContext dataContext = new MongoDbDataContext(db);

        assertTrue(dataContext.getDefaultSchema().getTableNames().contains(getCollectionName()));
        Table table = dataContext.getDefaultSchema().getTableByName(getCollectionName());
        assertEquals("[_id, baz, foo, id, list, name]", Arrays.toString(table.getColumnNames().toArray()));

        assertEquals(ColumnType.MAP, table.getColumnByName("baz").getType());
        assertEquals(ColumnType.STRING, table.getColumnByName("foo").getType());
        assertEquals(ColumnType.LIST, table.getColumnByName("list").getType());
        assertEquals(ColumnType.INTEGER, table.getColumnByName("id").getType());
        assertEquals(ColumnType.ROWID, table.getColumnByName("_id").getType());

        DataSet ds = dataContext.query().from(getCollectionName()).select("name").and("foo").and("baz").and("list")
                .where("id").greaterThan(800).or("foo").isEquals("bar").execute();
        assertEquals(MongoDbDataSet.class, ds.getClass());
        assertFalse(((MongoDbDataSet) ds).isQueryPostProcessed());
        try {
            assertTrue(ds.next());
            assertEquals(
                    "Row[values=[record no. 0, bar, {count=0, constant=foobarbaz}, [ \"l1\" , \"l2\" , \"l3\" , 0]]]",
                    ds.getRow().toString());

            assertTrue(ds.next());
            assertEquals(
                    "Row[values=[record no. 5, bar, {count=5, constant=foobarbaz}, [ \"l1\" , \"l2\" , \"l3\" , 5]]]",
                    ds.getRow().toString());

            assertTrue(ds.next());
            assertEquals(
                    "Row[values=[record no. 10, bar, {count=10, constant=foobarbaz}, [ \"l1\" , \"l2\" , \"l3\" , 10]]]",
                    ds.getRow().toString());

            for (int j = 15; j < 801; j++) {
                if (j % 5 == 0) {
                    assertTrue(ds.next());
                    assertEquals("Row[values=[record no. " + j + ", bar, {count=" + j
                            + ", constant=foobarbaz}, [ \"l1\" , \"l2\" , \"l3\" , " + j + "]]]", ds.getRow()
                            .toString());
                }
            }

            assertTrue(ds.next());
            assertTrue(ds.getRow().getValue(2) instanceof Map);
            assertEquals(LinkedHashMap.class, ds.getRow().getValue(2).getClass());

            assertTrue("unexpected type: " + ds.getRow().getValue(3).getClass(),
                    ds.getRow().getValue(3) instanceof List);
            assertEquals(BasicDBList.class, ds.getRow().getValue(3).getClass());

            assertEquals(
                    "Row[values=[record no. 801, baz, {count=801, constant=foobarbaz}, [ \"l1\" , \"l2\" , \"l3\" , 801]]]",
                    ds.getRow().toString());
            assertTrue(ds.next());
            assertEquals(
                    "Row[values=[record no. 802, baz, {count=802, constant=foobarbaz}, [ \"l1\" , \"l2\" , \"l3\" , 802]]]",
                    ds.getRow().toString());
            assertTrue(ds.next());
            assertEquals(
                    "Row[values=[record no. 803, baz, {count=803, constant=foobarbaz}, [ \"l1\" , \"l2\" , \"l3\" , 803]]]",
                    ds.getRow().toString());
            assertTrue(ds.next());
            assertEquals(
                    "Row[values=[record no. 804, baz, {count=804, constant=foobarbaz}, [ \"l1\" , \"l2\" , \"l3\" , 804]]]",
                    ds.getRow().toString());
            assertTrue(ds.next());
            assertEquals(
                    "Row[values=[record no. 805, bar, {count=805, constant=foobarbaz}, [ \"l1\" , \"l2\" , \"l3\" , 805]]]",
                    ds.getRow().toString());

            for (int i = 0; i < 194; i++) {
                assertTrue(ds.next());
            }
            assertEquals(
                    "Row[values=[record no. 999, baz, {count=999, constant=foobarbaz}, [ \"l1\" , \"l2\" , \"l3\" , 999]]]",
                    ds.getRow().toString());
            assertFalse(ds.next());
        } finally {
            ds.close();
        }

        ds = dataContext.query().from(getCollectionName()).select("id").and("name").where("id").in(2, 6, 8, 9)
                .execute();
        assertTrue(ds.next());
        assertEquals("Row[values=[2, record no. 2]]", ds.getRow().toString());
        assertTrue(ds.next());
        assertEquals("Row[values=[6, record no. 6]]", ds.getRow().toString());
        assertTrue(ds.next());
        assertEquals("Row[values=[8, record no. 8]]", ds.getRow().toString());
        assertTrue(ds.next());
        assertEquals("Row[values=[9, record no. 9]]", ds.getRow().toString());
        assertFalse(ds.next());
        ds.close();

        ds = dataContext.query().from(getCollectionName()).select("id").and("name").where("foo").isEquals("bar")
                .execute();
        assertEquals(MongoDbDataSet.class, ds.getClass());
        assertFalse(((MongoDbDataSet) ds).isQueryPostProcessed());

        try {
            List<Object[]> objectArrays = ds.toObjectArrays();
            assertEquals(200, objectArrays.size());
            assertEquals("[0, record no. 0]", Arrays.toString(objectArrays.get(0)));
        } finally {
            ds.close();
        }

        // test GREATER_THAN_OR_EQUAL
        ds = dataContext.query().from(getCollectionName()).select("id").and("name").where("id")
                .greaterThanOrEquals(500).and("foo").isEquals("bar").execute();
        assertEquals(MongoDbDataSet.class, ds.getClass());
        assertFalse(((MongoDbDataSet) ds).isQueryPostProcessed());

        try {
            List<Object[]> objectArrays = ds.toObjectArrays();
            assertEquals(100, objectArrays.size());
            assertEquals("[500, record no. 500]", Arrays.toString(objectArrays.get(0)));
        } finally {
            ds.close();
        }

        ds = dataContext.query().from(getCollectionName()).select("id").and("name").where("id")
                .greaterThanOrEquals(501).and("foo").isEquals("bar").execute();
        assertEquals(MongoDbDataSet.class, ds.getClass());
        assertFalse(((MongoDbDataSet) ds).isQueryPostProcessed());

        try {
            List<Object[]> objectArrays = ds.toObjectArrays();
            assertEquals(99, objectArrays.size());
            assertEquals("[505, record no. 505]", Arrays.toString(objectArrays.get(0)));
        } finally {
            ds.close();
        }

        // test LESS_THAN_OR_EQUAL

        ds = dataContext.query().from(getCollectionName()).select("id").and("name").where("id").lessThanOrEquals(500)
                .and("foo").isEquals("bar").execute();
        assertEquals(MongoDbDataSet.class, ds.getClass());
        assertFalse(((MongoDbDataSet) ds).isQueryPostProcessed());

        try {
            List<Object[]> objectArrays = ds.toObjectArrays();
            assertEquals(101, objectArrays.size());
            assertEquals("[500, record no. 500]", Arrays.toString(objectArrays.get(100)));
        } finally {
            ds.close();
        }

        ds = dataContext.query().from(getCollectionName()).select("id").and("name").where("id").lessThanOrEquals(499)
                .and("foo").isEquals("bar").execute();
        assertEquals(MongoDbDataSet.class, ds.getClass());
        assertFalse(((MongoDbDataSet) ds).isQueryPostProcessed());

        try {
            List<Object[]> objectArrays = ds.toObjectArrays();
            assertEquals(100, objectArrays.size());
            assertEquals("[495, record no. 495]", Arrays.toString(objectArrays.get(99)));
        } finally {
            ds.close();
        }

        // test a primary key lookup query
        BasicDBObject dbRow = new BasicDBObject();
        dbRow.put("_id", 123456);
        dbRow.put("id", 123456);
        dbRow.put("name", "record no. " + 123456);
        dbRow.put("foo", "bar123456");
        BasicDBObject nestedObj = new BasicDBObject();
        nestedObj.put("count", 123456);
        nestedObj.put("constant", "foobarbaz");
        dbRow.put("baz", nestedObj);

        dbRow.put("list", Arrays.<Object> asList("l1", "l2", "l3", 123456));

        col.insert(dbRow);

        ds = dataContext.query().from(getCollectionName()).select("id").and("name").where("_id").eq(123456).execute();
        assertTrue(ds.next());
        assertEquals("Row[values=[123456, record no. 123456]]", ds.getRow().toString());
        assertFalse(ds.next());

        // do a query that we cannot push to mongo
        // Replace column index 0 by 1
        ds = dataContext.query().from(getCollectionName())
                .select(FunctionType.SUM, dataContext.getDefaultSchema().getTables().get(0).getColumnByName("id"))
                .where("foo").isEquals("bar").execute();
        assertEquals(InMemoryDataSet.class, ds.getClass());

        ds.close();
    }

    public void testCreateAndWriteData() throws Exception {
        if (!isConfigured()) {
            System.err.println(getInvalidConfigurationMessage());
            return;
        }
        final MongoDbDataContext dc = new MongoDbDataContext(db);
        final Schema defaultSchema = dc.getDefaultSchema();

        dc.executeUpdate(new UpdateScript() {
            @Override
            public void run(UpdateCallback callback) {
                for (Table table : defaultSchema.getTables()) {
                    callback.deleteFrom(table).execute();
                }
            }
        });

        assertEquals(0, defaultSchema.getTableCount());

        dc.executeUpdate(new UpdateScript() {

            @Override
            public void run(UpdateCallback callback) {
                Table table = callback.createTable(defaultSchema, "some_entries").withColumn("foo").withColumn("bar")
                        .withColumn("baz").withColumn("list").execute();

                callback.insertInto(table).value("foo", 1).value("bar", "hello").execute();
                callback.insertInto(table).value("foo", 2).value("bar", "world").execute();
                callback.insertInto(table).value("foo", 3).value("bar", "hi").execute();

                Map<String, Object> nestedObj = new HashMap<String, Object>();
                nestedObj.put("foo", "bar");
                nestedObj.put("123", 456);

                callback.insertInto(table).value("foo", 4).value("bar", "there").value("baz", nestedObj)
                        .value("list", Arrays.asList(1, 2, 3)).execute();
            }
        });

        DataSet dataSet;
        assertEquals(1, defaultSchema.getTableCount());

        // "Pure" SELECT COUNT(*) query
        dataSet = dc.query().from("some_entries").selectCount().execute();
        dataSet.close();
        assertTrue(dataSet.next());
        assertEquals(1, dataSet.getSelectItems().size());
        assertEquals(SelectItem.getCountAllItem(), dataSet.getSelectItems().get(0));
        assertEquals(4l, dataSet.getRow().getValue(SelectItem.getCountAllItem()));
        assertFalse(dataSet.next());
        assertEquals(InMemoryDataSet.class, dataSet.getClass());

        // A conditional SELECT COUNT(*) query
        dataSet = dc.query().from("some_entries").selectCount().where("foo").greaterThan(2).execute();
        dataSet.close();
        assertTrue(dataSet.next());
        assertEquals(1, dataSet.getSelectItems().size());
        assertEquals(SelectItem.getCountAllItem(), dataSet.getSelectItems().get(0));
        assertEquals(2l, dataSet.getRow().getValue(SelectItem.getCountAllItem()));
        assertFalse(dataSet.next());
        assertEquals(InMemoryDataSet.class, dataSet.getClass());

        // Select columns
        dataSet = dc.query().from("some_entries").select("foo").and("bar").and("baz").and("list").execute();
        assertTrue(dataSet.next());
        assertEquals("Row[values=[1, hello, null, null]]", dataSet.getRow().toString());
        assertTrue(dataSet.next());
        assertEquals("Row[values=[2, world, null, null]]", dataSet.getRow().toString());
        assertTrue(dataSet.next());
        assertEquals("Row[values=[3, hi, null, null]]", dataSet.getRow().toString());
        assertTrue(dataSet.next());
        assertEquals("Row[values=[4, there, {123=456, foo=bar}, [ 1 , 2 , 3]]]", dataSet.getRow().toString());
        assertFalse(dataSet.next());
        dataSet.close();
        assertEquals(MongoDbDataSet.class, dataSet.getClass());

        // delete some records
        dc.executeUpdate(new UpdateScript() {
            @Override
            public void run(UpdateCallback callback) {
                callback.deleteFrom("some_entries").where("foo").greaterThan(2).where("baz").isNotNull().execute();
            }
        });

        dataSet = dc.query().from("some_entries").select("foo").execute();
        assertTrue(dataSet.next());
        assertEquals("Row[values=[1]]", dataSet.getRow().toString());
        assertTrue(dataSet.next());
        assertEquals("Row[values=[2]]", dataSet.getRow().toString());
        assertTrue(dataSet.next());
        assertEquals("Row[values=[3]]", dataSet.getRow().toString());
        assertFalse(dataSet.next());
        dataSet.close();
        assertEquals(MongoDbDataSet.class, dataSet.getClass());

        // drop the collection
        dc.executeUpdate(new UpdateScript() {
            @Override
            public void run(UpdateCallback callback) {
                callback.dropTable("some_entries").execute();
            }
        });

        assertNull(dc.getTableByQualifiedLabel("some_entries"));

        dc.refreshSchemas();
        assertEquals(0, defaultSchema.getTableCount());
    }

    public void testSelectWithLikeOperator() throws Exception {
        if (!isConfigured()) {
            System.err.println(getInvalidConfigurationMessage());
            return;
        }

        DBCollection col = db.createCollection(getCollectionName(), new BasicDBObject());

        // delete if already exists
        {
            col.drop();
            col = db.createCollection(getCollectionName(), new BasicDBObject());
        }

        final BasicDBObject dbRow = new BasicDBObject();
        dbRow.append("name", new BasicDBObject().append("first", "John").append("last", "Doe"));
        dbRow.append("gender", "MALE");
        col.insert(dbRow);

        final BasicDBObject dbRow2 = new BasicDBObject();
        dbRow2.append("name", new BasicDBObject().append("first", "Mary").append("last", "Johnson"));
        dbRow2.append("gender", "FEMALE");
        col.insert(dbRow2);

        final BasicDBObject dbRow3 = new BasicDBObject();
        dbRow3.append("name", new BasicDBObject().append("first", "X").append("last", "Unknown"));
        dbRow3.append("gender", "UNKNOWN");
        col.insert(dbRow3);

        final MongoDbDataContext dc = new MongoDbDataContext(db, new SimpleTableDef(getCollectionName(), new String[] {
                "name.first", "name.last", "gender", "addresses", "addresses[0].city", "addresses[0].country",
                "addresses[5].foobar" }));

        final DataSet ds1 = dc.executeQuery("select * from my_collection where gender LIKE '%MALE%'");
        final DataSet ds2 = dc.executeQuery("select * from my_collection where gender LIKE 'MALE%'");
        final DataSet ds3 = dc.executeQuery("select * from my_collection where gender LIKE '%NK%OW%'");
        final DataSet ds4 = dc.executeQuery("select * from my_collection where gender LIKE '%MALE'");
        try {
            assertTrue(ds1.next());
            assertTrue(ds1.next());
            assertFalse(ds1.next());
            assertTrue(ds2.next());
            assertFalse(ds2.next());
            assertTrue(ds3.next());
            assertFalse(ds3.next());
            assertTrue(ds4.next());
            assertTrue(ds4.next());
            assertFalse(ds4.next());
        } finally {
            ds1.close();
            ds2.close();
            ds3.close();
            ds4.close();
        }
    }

    public void testSelectWithAlias() throws Exception {
        if (!isConfigured()) {
            System.err.println(getInvalidConfigurationMessage());
            return;
        }

        DBCollection col = db.createCollection(getCollectionName(), new BasicDBObject());

        // delete if already exists
        {
            col.drop();
            col = db.createCollection(getCollectionName(), new BasicDBObject());
        }

        final BasicDBObject dbRow = new BasicDBObject();
        dbRow.append("name", new BasicDBObject().append("first", "John").append("last", "Doe"));
        dbRow.append("gender", "MALE");
        col.insert(dbRow);

        final MongoDbDataContext dc = new MongoDbDataContext(db, new SimpleTableDef(getCollectionName(), new String[] {
                "name.first", "name.last", "gender", "addresses", "addresses[0].city", "addresses[0].country",
                "addresses[5].foobar" }));

        final DataSet ds1 = dc.executeQuery("select gender AS my_gender, name.first AS my_name from my_collection where gender LIKE '%MALE%'");
        final SelectItem[] selectItems = ds1.getSelectItems().toArray(new SelectItem[ds1.getSelectItems().size()]);
        SelectItem firstSelectItem = selectItems[0];
        SelectItem secondSelectItem = selectItems[1];
        try {
            assertNotNull(firstSelectItem.getAlias());
            assertNotNull(secondSelectItem.getAlias());
        } finally {
            ds1.close();
        }
    }
}
