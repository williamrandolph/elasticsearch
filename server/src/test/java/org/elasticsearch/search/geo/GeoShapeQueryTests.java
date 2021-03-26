/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.geo;

import com.carrotsearch.randomizedtesting.generators.RandomNumbers;
import org.apache.lucene.geo.GeoTestUtil;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.CheckedSupplier;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.geo.ShapeRelation;
import org.elasticsearch.common.geo.builders.CircleBuilder;
import org.elasticsearch.common.geo.builders.CoordinatesBuilder;
import org.elasticsearch.common.geo.builders.EnvelopeBuilder;
import org.elasticsearch.common.geo.builders.GeometryCollectionBuilder;
import org.elasticsearch.common.geo.builders.LineStringBuilder;
import org.elasticsearch.common.geo.builders.MultiPointBuilder;
import org.elasticsearch.common.geo.builders.PointBuilder;
import org.elasticsearch.common.geo.builders.PolygonBuilder;
import org.elasticsearch.common.geo.builders.ShapeBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.mapper.LegacyGeoShapeFieldMapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.query.ExistsQueryBuilder;
import org.elasticsearch.index.query.GeoShapeQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.geo.RandomShapeGenerator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.spatial4j.shape.Rectangle;

import java.io.IOException;
import java.util.Locale;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.geoIntersectionQuery;
import static org.elasticsearch.index.query.QueryBuilders.geoShapeQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.test.geo.RandomShapeGenerator.createGeometryCollectionWithin;
import static org.elasticsearch.test.geo.RandomShapeGenerator.xRandomPoint;
import static org.elasticsearch.test.geo.RandomShapeGenerator.xRandomRectangle;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class GeoShapeQueryTests extends GeoQueryTests {
    protected static final String[] PREFIX_TREES = new String[] {
        LegacyGeoShapeFieldMapper.PrefixTrees.GEOHASH,
        LegacyGeoShapeFieldMapper.PrefixTrees.QUADTREE
    };

    @Override
    protected XContentBuilder createDefaultMapping() throws Exception {
        XContentBuilder xcb = XContentFactory.jsonBuilder().startObject()
            .startObject("properties").startObject("geo")
            .field("type", "geo_shape")
            .endObject()
            .endObject()
            .endObject();
        return xcb;
    }

    protected XContentBuilder createPrefixTreeMapping(String tree) throws Exception {
        XContentBuilder xcb = XContentFactory.jsonBuilder().startObject()
            .startObject("properties").startObject("geo")
            .field("type", "geo_shape")
            .field("tree", tree)
            .endObject()
            .endObject()
            .endObject();

        return xcb;
    }

    protected XContentBuilder createRandomMapping() throws Exception {
        XContentBuilder xcb = XContentFactory.jsonBuilder().startObject()
            .startObject("properties").startObject("geo")
            .field("type", "geo_shape");
        if (randomBoolean()) {
            xcb = xcb.field("tree", randomFrom(PREFIX_TREES));
        }
        xcb = xcb.endObject().endObject().endObject();

        return xcb;
    }

    public void testShapeFetchingPath() throws Exception {
        createIndex("shapes");
        String mapping = Strings.toString(createDefaultMapping());
        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        ensureGreen();

        String geo = "\"geo\" : {\"type\":\"polygon\", \"coordinates\":[[[-10,-10],[10,-10],[10,10],[-10,10],[-10,-10]]]}";

        client().prepareIndex("shapes").setId("1")
            .setSource(
                String.format(
                    Locale.ROOT, "{ %s, \"1\" : { %s, \"2\" : { %s, \"3\" : { %s } }} }", geo, geo, geo, geo
                ), XContentType.JSON)
            .setRefreshPolicy(IMMEDIATE).get();
        client().prepareIndex("test").setId("1")
            .setSource(jsonBuilder().startObject().startObject("geo")
                .field("type", "polygon")
                .startArray("coordinates").startArray()
                .startArray().value(-20).value(-20).endArray()
                .startArray().value(20).value(-20).endArray()
                .startArray().value(20).value(20).endArray()
                .startArray().value(-20).value(20).endArray()
                .startArray().value(-20).value(-20).endArray()
                .endArray().endArray()
                .endObject().endObject()).setRefreshPolicy(IMMEDIATE).get();

        GeoShapeQueryBuilder filter = QueryBuilders.geoShapeQuery("geo", "1").relation(ShapeRelation.INTERSECTS)
            .indexedShapeIndex("shapes")
            .indexedShapePath("geo");
        SearchResponse result = client().prepareSearch("test").setQuery(QueryBuilders.matchAllQuery())
            .setPostFilter(filter).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
        filter = QueryBuilders.geoShapeQuery("geo", "1").relation(ShapeRelation.INTERSECTS)
            .indexedShapeIndex("shapes")
            .indexedShapePath("1.geo");
        result = client().prepareSearch("test").setQuery(QueryBuilders.matchAllQuery())
            .setPostFilter(filter).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
        filter = QueryBuilders.geoShapeQuery("geo", "1").relation(ShapeRelation.INTERSECTS)
            .indexedShapeIndex("shapes")
            .indexedShapePath("1.2.geo");
        result = client().prepareSearch("test").setQuery(QueryBuilders.matchAllQuery())
            .setPostFilter(filter).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
        filter = QueryBuilders.geoShapeQuery("geo", "1").relation(ShapeRelation.INTERSECTS)
            .indexedShapeIndex("shapes")
            .indexedShapePath("1.2.3.geo");
        result = client().prepareSearch("test").setQuery(QueryBuilders.matchAllQuery())
            .setPostFilter(filter).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);

        // now test the query variant
        GeoShapeQueryBuilder query = QueryBuilders.geoShapeQuery("geo", "1")
            .indexedShapeIndex("shapes")
            .indexedShapePath("geo");
        result = client().prepareSearch("test").setQuery(query).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
        query = QueryBuilders.geoShapeQuery("geo", "1")
            .indexedShapeIndex("shapes")
            .indexedShapePath("1.geo");
        result = client().prepareSearch("test").setQuery(query).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
        query = QueryBuilders.geoShapeQuery("geo", "1")
            .indexedShapeIndex("shapes")
            .indexedShapePath("1.2.geo");
        result = client().prepareSearch("test").setQuery(query).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
        query = QueryBuilders.geoShapeQuery("geo", "1")
            .indexedShapeIndex("shapes")
            .indexedShapePath("1.2.3.geo");
        result = client().prepareSearch("test").setQuery(query).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
    }

    public void testRandomGeoCollectionQuery() throws Exception {
        // Create a random geometry collection to index.
        GeometryCollectionBuilder gcb = RandomShapeGenerator.createGeometryCollection(random());;

        org.apache.lucene.geo.Polygon randomPoly = GeoTestUtil.nextPolygon();

        assumeTrue("Skipping the check for the polygon with a degenerated dimension",
            randomPoly.maxLat - randomPoly.minLat > 8.4e-8 &&  randomPoly.maxLon - randomPoly.minLon > 8.4e-8);

        CoordinatesBuilder cb = new CoordinatesBuilder();
        for (int i = 0; i < randomPoly.numPoints(); ++i) {
            cb.coordinate(randomPoly.getPolyLon(i), randomPoly.getPolyLat(i));
        }
        gcb.shape(new PolygonBuilder(cb));

        logger.info("Created Random GeometryCollection containing {} shapes", gcb.numShapes());

        XContentBuilder mapping = createRandomMapping();
        Settings settings = Settings.builder().put("index.number_of_shards", 1).build();
        client().admin().indices().prepareCreate("test").setMapping(mapping).setSettings(settings).get();
        ensureGreen();

        XContentBuilder docSource = gcb.toXContent(jsonBuilder().startObject().field("geo"), null).endObject();
        client().prepareIndex("test").setId("1").setSource(docSource).setRefreshPolicy(IMMEDIATE).get();

        // Create a random geometry collection to query
        GeometryCollectionBuilder queryCollection = RandomShapeGenerator.createGeometryCollection(random());
        queryCollection.shape(new PolygonBuilder(cb));

        GeoShapeQueryBuilder geoShapeQueryBuilder = QueryBuilders.geoShapeQuery("geo", queryCollection);
        geoShapeQueryBuilder.relation(ShapeRelation.INTERSECTS);
        SearchResponse result = client().prepareSearch("test").setQuery(geoShapeQueryBuilder).get();
        assertSearchResponse(result);
        assertTrue("query: " + geoShapeQueryBuilder.toString() + " doc: " + Strings.toString(docSource),
            result.getHits().getTotalHits().value > 0);
    }

    // Test for issue #34418
    public void testEnvelopeSpanningDateline() throws Exception {
        XContentBuilder mapping = createDefaultMapping();
        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        ensureGreen();

        String doc1 = "{\"geo\": {\r\n" + "\"coordinates\": [\r\n" + "-33.918711,\r\n" + "18.847685\r\n" + "],\r\n" +
            "\"type\": \"Point\"\r\n" + "}}";
        client().index(new IndexRequest("test").id("1").source(doc1, XContentType.JSON).setRefreshPolicy(IMMEDIATE)).actionGet();

        String doc2 = "{\"geo\": {\r\n" + "\"coordinates\": [\r\n" + "-49.0,\r\n" + "18.847685\r\n" + "],\r\n" +
            "\"type\": \"Point\"\r\n" + "}}";
        client().index(new IndexRequest("test").id("2").source(doc2, XContentType.JSON).setRefreshPolicy(IMMEDIATE)).actionGet();

        String doc3 = "{\"geo\": {\r\n" + "\"coordinates\": [\r\n" + "49.0,\r\n" + "18.847685\r\n" + "],\r\n" +
            "\"type\": \"Point\"\r\n" + "}}";
        client().index(new IndexRequest("test").id("3").source(doc3, XContentType.JSON).setRefreshPolicy(IMMEDIATE)).actionGet();

        @SuppressWarnings("unchecked") CheckedSupplier<GeoShapeQueryBuilder, IOException> querySupplier = randomFrom(
            () -> QueryBuilders.geoShapeQuery(
                "geo",
                new EnvelopeBuilder(new Coordinate(-21, 44), new Coordinate(-39, 9))
            ).relation(ShapeRelation.WITHIN),
            () -> {
                XContentBuilder builder = XContentFactory.jsonBuilder().startObject()
                    .startObject("geo")
                    .startObject("shape")
                    .field("type", "envelope")
                    .startArray("coordinates")
                    .startArray().value(-21).value(44).endArray()
                    .startArray().value(-39).value(9).endArray()
                    .endArray()
                    .endObject()
                    .field("relation", "within")
                    .endObject()
                    .endObject();
                try (XContentParser parser = createParser(builder)){
                    parser.nextToken();
                    return GeoShapeQueryBuilder.fromXContent(parser);
                }
            },
            () -> {
                XContentBuilder builder = XContentFactory.jsonBuilder().startObject()
                    .startObject("geo")
                    .field("shape", "BBOX (-21, -39, 44, 9)")
                    .field("relation", "within")
                    .endObject()
                    .endObject();
                try (XContentParser parser = createParser(builder)){
                    parser.nextToken();
                    return GeoShapeQueryBuilder.fromXContent(parser);
                }
            }
        );

        SearchResponse response = client().prepareSearch("test")
            .setQuery(querySupplier.get())
            .get();
        assertEquals(2, response.getHits().getTotalHits().value);
        assertNotEquals("1", response.getHits().getAt(0).getId());
        assertNotEquals("1", response.getHits().getAt(1).getId());
    }

    public void testGeometryCollectionRelations() throws Exception {
        XContentBuilder mapping = createDefaultMapping();
        Settings settings = Settings.builder().put("index.number_of_shards", 1).build();
        client().admin().indices().prepareCreate("test").setMapping(mapping).setSettings(settings).get();
        ensureGreen();

        EnvelopeBuilder envelopeBuilder = new EnvelopeBuilder(new Coordinate(-10, 10), new Coordinate(10, -10));

        client().index(new IndexRequest("test")
            .source(jsonBuilder().startObject().field("geo", envelopeBuilder).endObject())
            .setRefreshPolicy(IMMEDIATE)).actionGet();

        {
            // A geometry collection that is fully within the indexed shape
            GeometryCollectionBuilder builder = new GeometryCollectionBuilder();
            builder.shape(new PointBuilder(1, 2));
            builder.shape(new PointBuilder(-2, -1));
            SearchResponse response = client().prepareSearch("test")
                .setQuery(geoShapeQuery("geo", builder.buildGeometry()).relation(ShapeRelation.CONTAINS))
                .get();
            assertEquals(1, response.getHits().getTotalHits().value);
            response = client().prepareSearch("test")
                .setQuery(geoShapeQuery("geo", builder.buildGeometry()).relation(ShapeRelation.INTERSECTS))
                .get();
            assertEquals(1, response.getHits().getTotalHits().value);
            response = client().prepareSearch("test")
                .setQuery(geoShapeQuery("geo", builder.buildGeometry()).relation(ShapeRelation.DISJOINT))
                .get();
            assertEquals(0, response.getHits().getTotalHits().value);
        }
        // A geometry collection that is partially within the indexed shape
        {
            GeometryCollectionBuilder builder = new GeometryCollectionBuilder();
            builder.shape(new PointBuilder(1, 2));
            builder.shape(new PointBuilder(20, 30));
            SearchResponse response = client().prepareSearch("test")
                .setQuery(geoShapeQuery("geo", builder.buildGeometry()).relation(ShapeRelation.CONTAINS))
                .get();
            assertEquals(0, response.getHits().getTotalHits().value);
            response = client().prepareSearch("test")
                .setQuery(geoShapeQuery("geo", builder.buildGeometry()).relation(ShapeRelation.INTERSECTS))
                .get();
            assertEquals(1, response.getHits().getTotalHits().value);
            response = client().prepareSearch("test")
                .setQuery(geoShapeQuery("geo", builder.buildGeometry()).relation(ShapeRelation.DISJOINT))
                .get();
            assertEquals(0, response.getHits().getTotalHits().value);
        }
        {
            // A geometry collection that is disjoint with the indexed shape
            GeometryCollectionBuilder builder = new GeometryCollectionBuilder();
            builder.shape(new PointBuilder(-20, -30));
            builder.shape(new PointBuilder(20, 30));
            SearchResponse response = client().prepareSearch("test")
                .setQuery(geoShapeQuery("geo", builder.buildGeometry()).relation(ShapeRelation.CONTAINS))
                .get();
            assertEquals(0, response.getHits().getTotalHits().value);
            response = client().prepareSearch("test")
                .setQuery(geoShapeQuery("geo", builder.buildGeometry()).relation(ShapeRelation.INTERSECTS))
                .get();
            assertEquals(0, response.getHits().getTotalHits().value);
            response = client().prepareSearch("test")
                .setQuery(geoShapeQuery("geo", builder.buildGeometry()).relation(ShapeRelation.DISJOINT))
                .get();
            assertEquals(1, response.getHits().getTotalHits().value);
        }
    }

    public void testEdgeCases() throws Exception {
        XContentBuilder xcb = XContentFactory.jsonBuilder().startObject()
            .startObject("properties").startObject("geo")
            .field("type", "geo_shape")
            .endObject().endObject().endObject();
        String mapping = Strings.toString(xcb);
        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        ensureGreen();

        client().prepareIndex("test").setId("blakely").setSource(jsonBuilder().startObject()
                .field("name", "Blakely Island")
                .startObject("geo")
                .field("type", "polygon")
                .startArray("coordinates").startArray()
                .startArray().value(-122.83).value(48.57).endArray()
                .startArray().value(-122.77).value(48.56).endArray()
                .startArray().value(-122.79).value(48.53).endArray()
                .startArray().value(-122.83).value(48.57).endArray() // close the polygon
                .endArray().endArray()
                .endObject()
                .endObject()).setRefreshPolicy(IMMEDIATE).get();

        EnvelopeBuilder query = new EnvelopeBuilder(new Coordinate(-122.88, 48.62), new Coordinate(-122.82, 48.54));

        // This search would fail if both geoshape indexing and geoshape filtering
        // used the bottom-level optimization in SpatialPrefixTree#recursiveGetNodes.
        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(geoIntersectionQuery("geo", query))
                .get();

        assertSearchResponse(searchResponse);
        assertThat(searchResponse.getHits().getTotalHits().value, equalTo(1L));
        assertThat(searchResponse.getHits().getHits().length, equalTo(1));
        assertThat(searchResponse.getHits().getAt(0).getId(), equalTo("blakely"));
    }

    public void testIndexedShapeReferenceSourceDisabled() throws Exception {
        String mapping = Strings.toString(createRandomMapping());
        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        createIndex("shapes", Settings.EMPTY, "shape_type", "_source", "enabled=false");
        ensureGreen();

        EnvelopeBuilder shape = new EnvelopeBuilder(new Coordinate(-45, 45), new Coordinate(45, -45));

        client().prepareIndex("shapes").setId("Big_Rectangle").setSource(jsonBuilder().startObject()
            .field("shape", shape).endObject()).setRefreshPolicy(IMMEDIATE).get();

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> client().prepareSearch("test")
            .setQuery(geoIntersectionQuery("geo", "Big_Rectangle")).get());
        assertThat(e.getMessage(), containsString("source disabled"));
    }

    public void testReusableBuilder() throws IOException {
        PolygonBuilder polygon = new PolygonBuilder(new CoordinatesBuilder()
                .coordinate(170, -10).coordinate(190, -10).coordinate(190, 10).coordinate(170, 10).close())
                .hole(new LineStringBuilder(new CoordinatesBuilder().coordinate(175, -5).coordinate(185, -5).coordinate(185, 5)
                        .coordinate(175, 5).close()));
        assertUnmodified(polygon);

        LineStringBuilder linestring = new LineStringBuilder(new CoordinatesBuilder()
                .coordinate(170, -10).coordinate(190, -10).coordinate(190, 10).coordinate(170, 10).close());
        assertUnmodified(linestring);
    }

    private void assertUnmodified(ShapeBuilder builder) throws IOException {
        String before = Strings.toString(jsonBuilder().startObject().field("area", builder).endObject());
        builder.buildS4J();
        String after = Strings.toString(jsonBuilder().startObject().field("area", builder).endObject());
        assertThat(before, equalTo(after));
    }

    /** tests querying a random geometry collection with a point */
    public void testPointQuery() throws Exception {
        // Create a random geometry collection to index.
        GeometryCollectionBuilder gcb = RandomShapeGenerator.createGeometryCollection(random());
        double[] pt = new double[] {GeoTestUtil.nextLongitude(), GeoTestUtil.nextLatitude()};
        PointBuilder pb = new PointBuilder(pt[0], pt[1]);
        gcb.shape(pb);

        // create mapping
        String mapping = Strings.toString(createRandomMapping());
        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        ensureGreen();

        XContentBuilder docSource = gcb.toXContent(jsonBuilder().startObject().field("geo"), null).endObject();
        client().prepareIndex("test").setId("1").setSource(docSource).setRefreshPolicy(IMMEDIATE).get();

        GeoShapeQueryBuilder geoShapeQueryBuilder = QueryBuilders.geoShapeQuery("geo", pb);
        geoShapeQueryBuilder.relation(ShapeRelation.INTERSECTS);
        SearchResponse result = client().prepareSearch("test").setQuery(geoShapeQueryBuilder).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
    }

    public void testContainsShapeQuery() throws Exception {
        // Create a random geometry collection.
        Rectangle mbr = xRandomRectangle(random(), xRandomPoint(random()), true);
        boolean usePrefixTrees = randomBoolean();
        GeometryCollectionBuilder gcb;
        if (usePrefixTrees) {
            gcb = createGeometryCollectionWithin(random(), mbr);
        } else {
            // vector strategy does not yet support multipoint queries
            gcb = new GeometryCollectionBuilder();
            int numShapes = RandomNumbers.randomIntBetween(random(), 1, 4);
            for (int i = 0; i < numShapes; ++i) {
                ShapeBuilder shape;
                do {
                    shape = RandomShapeGenerator.createShapeWithin(random(), mbr);
                } while (shape instanceof MultiPointBuilder);
                gcb.shape(shape);
            }
        }

        // don't use random mapping as permits quadtree
        String mapping = Strings.toString(
            usePrefixTrees ?
                createPrefixTreeMapping(LegacyGeoShapeFieldMapper.PrefixTrees.QUADTREE) :
                createDefaultMapping());
        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        ensureGreen();

        XContentBuilder docSource = gcb.toXContent(jsonBuilder().startObject().field("geo"), null).endObject();
        client().prepareIndex("test").setId("1").setSource(docSource).setRefreshPolicy(IMMEDIATE).get();

        // index the mbr of the collection
        EnvelopeBuilder env = new EnvelopeBuilder(new Coordinate(mbr.getMinX(), mbr.getMaxY()),
                new Coordinate(mbr.getMaxX(), mbr.getMinY()));
        docSource = env.toXContent(jsonBuilder().startObject().field("geo"), null).endObject();
        client().prepareIndex("test").setId("2").setSource(docSource).setRefreshPolicy(IMMEDIATE).get();

        ShapeBuilder filterShape = (gcb.getShapeAt(randomIntBetween(0, gcb.numShapes() - 1)));
        GeoShapeQueryBuilder filter = QueryBuilders.geoShapeQuery("geo", filterShape)
                .relation(ShapeRelation.CONTAINS);
        SearchResponse response = client().prepareSearch("test").setQuery(QueryBuilders.matchAllQuery())
                .setPostFilter(filter).get();
        assertSearchResponse(response);

        assertThat(response.getHits().getTotalHits().value, greaterThan(0L));
    }

    public void testExistsQuery() throws Exception {
        // Create a random geometry collection.
        GeometryCollectionBuilder gcb = RandomShapeGenerator.createGeometryCollection(random());
        logger.info("Created Random GeometryCollection containing {} shapes", gcb.numShapes());

        String mapping = Strings.toString(createRandomMapping());

        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        ensureGreen();

        XContentBuilder docSource = gcb.toXContent(jsonBuilder().startObject().field("geo"), null).endObject();
        client().prepareIndex("test").setId("1").setSource(docSource).setRefreshPolicy(IMMEDIATE).get();

        ExistsQueryBuilder eqb = QueryBuilders.existsQuery("geo");
        SearchResponse result = client().prepareSearch("test").setQuery(eqb).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
    }

    public void testPointsOnly() throws Exception {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject()
                .startObject("properties").startObject("geo")
                .field("type", "geo_shape")
                .field("tree", randomBoolean() ? "quadtree" : "geohash")
                .field("tree_levels", "6")
                .field("distance_error_pct", "0.01")
                .field("points_only", true)
                .endObject()
                .endObject().endObject());

        client().admin().indices().prepareCreate("geo_points_only").setMapping(mapping).get();
        ensureGreen();

        ShapeBuilder shape = RandomShapeGenerator.createShape(random());
        try {
            client().prepareIndex("geo_points_only").setId("1")
                    .setSource(jsonBuilder().startObject().field("geo", shape).endObject())
                    .setRefreshPolicy(IMMEDIATE).get();
        } catch (MapperParsingException e) {
            // RandomShapeGenerator created something other than a POINT type, verify the correct exception is thrown
            assertThat(e.getCause().getMessage(), containsString("is configured for points only"));
            return;
        }

        // test that point was inserted
        SearchResponse response = client().prepareSearch("geo_points_only")
                .setQuery(geoIntersectionQuery("geo", shape))
                .get();

        assertEquals(1, response.getHits().getTotalHits().value);
    }

    public void testPointsOnlyExplicit() throws Exception {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject()
            .startObject("properties").startObject("geo")
            .field("type", "geo_shape")
            .field("tree", randomBoolean() ? "quadtree" : "geohash")
            .field("tree_levels", "6")
            .field("distance_error_pct", "0.01")
            .field("points_only", true)
            .endObject()
            .endObject().endObject());

        client().admin().indices().prepareCreate("geo_points_only").setMapping(mapping).get();
        ensureGreen();

        // MULTIPOINT
        ShapeBuilder shape = RandomShapeGenerator.createShape(random(), RandomShapeGenerator.ShapeType.MULTIPOINT);
        client().prepareIndex("geo_points_only").setId("1")
            .setSource(jsonBuilder().startObject().field("geo", shape).endObject())
            .setRefreshPolicy(IMMEDIATE).get();

        // POINT
        shape = RandomShapeGenerator.createShape(random(), RandomShapeGenerator.ShapeType.POINT);
        client().prepareIndex("geo_points_only").setId("2")
            .setSource(jsonBuilder().startObject().field("geo", shape).endObject())
            .setRefreshPolicy(IMMEDIATE).get();

        // test that point was inserted
        SearchResponse response = client().prepareSearch("geo_points_only")
            .setQuery(matchAllQuery())
            .get();

        assertEquals(2, response.getHits().getTotalHits().value);
    }

    public void testIndexedShapeReference() throws Exception {
        String mapping = Strings.toString(createRandomMapping());
        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        createIndex("shapes");
        ensureGreen();

        EnvelopeBuilder shape = new EnvelopeBuilder(new Coordinate(-45, 45), new Coordinate(45, -45));

        client().prepareIndex("shapes").setId("Big_Rectangle").setSource(jsonBuilder().startObject()
            .field("shape", shape).endObject()).setRefreshPolicy(IMMEDIATE).get();
        client().prepareIndex("test").setId("1").setSource(jsonBuilder().startObject()
            .field("name", "Document 1")
            .startObject("geo")
            .field("type", "point")
            .startArray("coordinates").value(-30).value(-30).endArray()
            .endObject()
            .endObject()).setRefreshPolicy(IMMEDIATE).get();

        SearchResponse searchResponse = client().prepareSearch("test")
            .setQuery(geoIntersectionQuery("geo", "Big_Rectangle"))
            .get();

        assertSearchResponse(searchResponse);
        assertThat(searchResponse.getHits().getTotalHits().value, equalTo(1L));
        assertThat(searchResponse.getHits().getHits().length, equalTo(1));
        assertThat(searchResponse.getHits().getAt(0).getId(), equalTo("1"));

        searchResponse = client().prepareSearch("test")
            .setQuery(geoShapeQuery("geo", "Big_Rectangle"))
            .get();

        assertSearchResponse(searchResponse);
        assertThat(searchResponse.getHits().getTotalHits().value, equalTo(1L));
        assertThat(searchResponse.getHits().getHits().length, equalTo(1));
        assertThat(searchResponse.getHits().getAt(0).getId(), equalTo("1"));
    }

    public void testFieldAlias() throws IOException {
        String mapping = Strings.toString(XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject("geo")
            .field("type", "geo_shape")
            .field("tree", randomBoolean() ? "quadtree" : "geohash")
            .endObject()
            .startObject("alias")
            .field("type", "alias")
            .field("path", "geo")
            .endObject()
            .endObject()
            .endObject());
        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        ensureGreen();

        ShapeBuilder shape = RandomShapeGenerator.createShape(random(), RandomShapeGenerator.ShapeType.MULTIPOINT);
        client().prepareIndex("test").setId("1")
            .setSource(jsonBuilder().startObject().field("geo", shape).endObject())
            .setRefreshPolicy(IMMEDIATE).get();

        SearchResponse response = client().prepareSearch("test")
            .setQuery(geoShapeQuery("alias", shape))
            .get();
        assertEquals(1, response.getHits().getTotalHits().value);
    }

    public void testQueryRandomGeoCollection() throws Exception {
        // Create a random geometry collection.
        String mapping = Strings.toString(createRandomMapping());
        GeometryCollectionBuilder gcb = RandomShapeGenerator.createGeometryCollection(random());
        org.apache.lucene.geo.Polygon randomPoly = GeoTestUtil.nextPolygon();
        CoordinatesBuilder cb = new CoordinatesBuilder();
        for (int i = 0; i < randomPoly.numPoints(); ++i) {
            cb.coordinate(randomPoly.getPolyLon(i), randomPoly.getPolyLat(i));
        }
        gcb.shape(new PolygonBuilder(cb));

        logger.info("Created Random GeometryCollection containing {} shapes", gcb.numShapes());

        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        ensureGreen();

        XContentBuilder docSource = gcb.toXContent(jsonBuilder().startObject().field("geo"), null).endObject();
        client().prepareIndex("test").setId("1").setSource(docSource).setRefreshPolicy(IMMEDIATE).get();

        ShapeBuilder filterShape = (gcb.getShapeAt(gcb.numShapes() - 1));

        GeoShapeQueryBuilder geoShapeQueryBuilder = QueryBuilders.geoShapeQuery("geo", filterShape);
        geoShapeQueryBuilder.relation(ShapeRelation.INTERSECTS);
        SearchResponse result = client().prepareSearch("test").setQuery(geoShapeQueryBuilder).get();
        assertSearchResponse(result);
        assumeTrue("Skipping the check for the polygon with a degenerated dimension until "
                +" https://issues.apache.org/jira/browse/LUCENE-8634 is fixed",
            randomPoly.maxLat - randomPoly.minLat > 8.4e-8 &&  randomPoly.maxLon - randomPoly.minLon > 8.4e-8);
        assertHitCount(result, 1);
    }

    public void testShapeFilterWithDefinedGeoCollection() throws Exception {
        String mapping = Strings.toString(createRandomMapping());
        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        ensureGreen();

        XContentBuilder docSource = jsonBuilder().startObject().startObject("geo")
            .field("type", "geometrycollection")
            .startArray("geometries")
            .startObject()
            .field("type", "point")
            .startArray("coordinates")
            .value(100.0).value(0.0)
            .endArray()
            .endObject()
            .startObject()
            .field("type", "linestring")
            .startArray("coordinates")
            .startArray()
            .value(101.0).value(0.0)
            .endArray()
            .startArray()
            .value(102.0).value(1.0)
            .endArray()
            .endArray()
            .endObject()
            .endArray()
            .endObject().endObject();
        client().prepareIndex("test").setId("1")
            .setSource(docSource).setRefreshPolicy(IMMEDIATE).get();

        GeoShapeQueryBuilder filter = QueryBuilders.geoShapeQuery(
            "geo",
            new GeometryCollectionBuilder()
                .polygon(
                    new PolygonBuilder(new CoordinatesBuilder().coordinate(99.0, -1.0).coordinate(99.0, 3.0)
                        .coordinate(103.0, 3.0).coordinate(103.0, -1.0)
                        .coordinate(99.0, -1.0)))).relation(ShapeRelation.INTERSECTS);
        SearchResponse result = client().prepareSearch("test").setQuery(QueryBuilders.matchAllQuery())
            .setPostFilter(filter).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
        filter = QueryBuilders.geoShapeQuery(
            "geo",
            new GeometryCollectionBuilder().polygon(
                new PolygonBuilder(new CoordinatesBuilder().coordinate(199.0, -11.0).coordinate(199.0, 13.0)
                    .coordinate(193.0, 13.0).coordinate(193.0, -11.0)
                    .coordinate(199.0, -11.0)))).relation(ShapeRelation.INTERSECTS);
        result = client().prepareSearch("test").setQuery(QueryBuilders.matchAllQuery())
            .setPostFilter(filter).get();
        assertSearchResponse(result);
        assertHitCount(result, 0);
        filter = QueryBuilders.geoShapeQuery("geo", new GeometryCollectionBuilder()
            .polygon(new PolygonBuilder(new CoordinatesBuilder().coordinate(99.0, -1.0).coordinate(99.0, 3.0).coordinate(103.0, 3.0)
                .coordinate(103.0, -1.0).coordinate(99.0, -1.0)))
            .polygon(
                new PolygonBuilder(new CoordinatesBuilder().coordinate(199.0, -11.0).coordinate(199.0, 13.0)
                    .coordinate(193.0, 13.0).coordinate(193.0, -11.0)
                    .coordinate(199.0, -11.0)))).relation(ShapeRelation.INTERSECTS);
        result = client().prepareSearch("test").setQuery(QueryBuilders.matchAllQuery())
            .setPostFilter(filter).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
        // no shape
        filter = QueryBuilders.geoShapeQuery("geo", new GeometryCollectionBuilder());
        result = client().prepareSearch("test").setQuery(QueryBuilders.matchAllQuery())
            .setPostFilter(filter).get();
        assertSearchResponse(result);
        assertHitCount(result, 0);
    }

    public void testDistanceQuery() throws Exception {
        String mapping = Strings.toString(createRandomMapping());
        client().admin().indices().prepareCreate("test_distance").setMapping(mapping).get();
        ensureGreen();

        CircleBuilder circleBuilder = new CircleBuilder().center(new Coordinate(1, 0)).radius(350, DistanceUnit.KILOMETERS);

        client().index(new IndexRequest("test_distance")
            .source(jsonBuilder().startObject().field("geo", new PointBuilder(2, 2)).endObject())
            .setRefreshPolicy(IMMEDIATE)).actionGet();
        client().index(new IndexRequest("test_distance")
            .source(jsonBuilder().startObject().field("geo", new PointBuilder(3, 1)).endObject())
            .setRefreshPolicy(IMMEDIATE)).actionGet();
        client().index(new IndexRequest("test_distance")
            .source(jsonBuilder().startObject().field("geo", new PointBuilder(-20, -30)).endObject())
            .setRefreshPolicy(IMMEDIATE)).actionGet();
        client().index(new IndexRequest("test_distance")
            .source(jsonBuilder().startObject().field("geo", new PointBuilder(20, 30)).endObject())
            .setRefreshPolicy(IMMEDIATE)).actionGet();

        SearchResponse response = client().prepareSearch("test_distance")
            .setQuery(QueryBuilders.geoShapeQuery("geo", circleBuilder.buildGeometry()).relation(ShapeRelation.WITHIN))
            .get();
        assertEquals(2, response.getHits().getTotalHits().value);
        response = client().prepareSearch("test_distance")
            .setQuery(QueryBuilders.geoShapeQuery("geo", circleBuilder.buildGeometry()).relation(ShapeRelation.INTERSECTS))
            .get();
        assertEquals(2, response.getHits().getTotalHits().value);
        response = client().prepareSearch("test_distance")
            .setQuery(QueryBuilders.geoShapeQuery("geo", circleBuilder.buildGeometry()).relation(ShapeRelation.DISJOINT))
            .get();
        assertEquals(2, response.getHits().getTotalHits().value);
        response = client().prepareSearch("test_distance")
            .setQuery(QueryBuilders.geoShapeQuery("geo", circleBuilder.buildGeometry()).relation(ShapeRelation.CONTAINS))
            .get();
        assertEquals(0, response.getHits().getTotalHits().value);
    }

    public void testIndexRectangleSpanningDateLine() throws Exception {
        String mapping = Strings.toString(createRandomMapping());

        client().admin().indices().prepareCreate("test").setMapping(mapping).get();
        ensureGreen();

        EnvelopeBuilder envelopeBuilder = new EnvelopeBuilder(new Coordinate(178, 10), new Coordinate(-178, -10));

        XContentBuilder docSource = envelopeBuilder.toXContent(jsonBuilder().startObject().field("geo"), null).endObject();
        client().prepareIndex("test").setId("1").setSource(docSource).setRefreshPolicy(IMMEDIATE).get();

        ShapeBuilder filterShape = new PointBuilder(179, 0);

        GeoShapeQueryBuilder geoShapeQueryBuilder = QueryBuilders.geoShapeQuery("geo", filterShape);
        geoShapeQueryBuilder.relation(ShapeRelation.INTERSECTS);
        SearchResponse result = client().prepareSearch("test").setQuery(geoShapeQueryBuilder).get();
        assertSearchResponse(result);
        assertHitCount(result, 1);
    }
}
