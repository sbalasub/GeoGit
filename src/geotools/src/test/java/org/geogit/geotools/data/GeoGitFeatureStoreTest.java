/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2011, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geogit.geotools.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.geogit.api.Ref;
import org.geogit.api.RevCommit;
import org.geogit.api.porcelain.BranchCreateOp;
import org.geogit.api.porcelain.CommitOp;
import org.geogit.api.porcelain.LogOp;
import org.geogit.test.integration.RepositoryTestCase;
import org.geotools.data.DataUtilities;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.Transaction;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.factory.Hints;
import org.geotools.feature.FeatureCollection;
import org.junit.Test;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.FilterFactory2;
import org.opengis.filter.Id;
import org.opengis.filter.identity.FeatureId;
import org.opengis.filter.identity.ResourceId;

public class GeoGitFeatureStoreTest extends RepositoryTestCase {

    private static final FilterFactory2 ff = CommonFactoryFinder.getFilterFactory2(null);

    private GeoGitDataStore dataStore;

    private GeogitFeatureStore points;

    @Override
    protected void setUpInternal() throws Exception {
        dataStore = new GeoGitDataStore(geogit);
        dataStore.createSchema(super.pointsType);
        dataStore.createSchema(super.linesType);

        points = (GeogitFeatureStore) dataStore.getFeatureSource(pointsTypeName);
    }

    @Override
    protected void tearDownInternal() throws Exception {
        dataStore.dispose();
        dataStore = null;
        points = null;
    }

    @Test
    public void testAddFeatures() throws Exception {

        FeatureCollection<SimpleFeatureType, SimpleFeature> collection;
        collection = DataUtilities.collection(Arrays.asList((SimpleFeature) points1,
                (SimpleFeature) points2, (SimpleFeature) points3));

        try {
            points.addFeatures(collection);
            fail("Expected UnsupportedOperationException on AUTO_COMMIT");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("AUTO_COMMIT"));
        }

        Transaction tx = new DefaultTransaction();
        points.setTransaction(tx);
        assertSame(tx, points.getTransaction());
        try {
            List<FeatureId> addedFeatures = points.addFeatures(collection);
            assertNotNull(addedFeatures);
            assertEquals(3, addedFeatures.size());

            for (FeatureId id : addedFeatures) {
                assertFalse(id instanceof ResourceId);
                assertNotNull(id.getFeatureVersion());
            }

            // assert transaction isolation

            assertEquals(3, points.getFeatures().size());
            assertEquals(0, dataStore.getFeatureSource(pointsTypeName).getFeatures().size());

            tx.commit();

            assertEquals(3, dataStore.getFeatureSource(pointsTypeName).getFeatures().size());
        } catch (Exception e) {
            tx.rollback();
            throw e;
        } finally {
            tx.close();
        }
    }

    @Test
    public void testAddFeaturesOnASeparateBranch() throws Exception {
        final String branchName = "addtest";
        final Ref branchRef = geogit.command(BranchCreateOp.class).setName(branchName).call();
        dataStore.setBranch(branchName);

        FeatureCollection<SimpleFeatureType, SimpleFeature> collection;
        collection = DataUtilities.collection(Arrays.asList((SimpleFeature) points1,
                (SimpleFeature) points2, (SimpleFeature) points3));

        Transaction tx = new DefaultTransaction();
        points.setTransaction(tx);
        assertSame(tx, points.getTransaction());
        try {
            List<FeatureId> addedFeatures = points.addFeatures(collection);
            assertNotNull(addedFeatures);
            assertEquals(3, addedFeatures.size());
            // assert transaction isolation
            assertEquals(3, points.getFeatures().size());
            assertEquals(0, dataStore.getFeatureSource(pointsTypeName).getFeatures().size());

            tx.commit();

            assertEquals(3, dataStore.getFeatureSource(pointsTypeName).getFeatures().size());
        } catch (Exception e) {
            tx.rollback();
            throw e;
        } finally {
            tx.close();
        }
    }

    @Test
    public void testUseProvidedFIDSupported() throws Exception {

        assertTrue(points.getQueryCapabilities().isUseProvidedFIDSupported());

        FeatureCollection<SimpleFeatureType, SimpleFeature> collection;
        collection = DataUtilities.collection(Arrays.asList((SimpleFeature) points1,
                (SimpleFeature) points2, (SimpleFeature) points3));

        Transaction tx = new DefaultTransaction();
        points.setTransaction(tx);
        try {
            List<FeatureId> newFids = points.addFeatures(collection);
            assertNotNull(newFids);
            assertEquals(3, newFids.size());

            FeatureId fid1 = newFids.get(0);
            FeatureId fid2 = newFids.get(1);
            FeatureId fid3 = newFids.get(2);

            // new ids should have been generated...
            assertFalse(idP1.equals(fid1.getID()));
            assertFalse(idP1.equals(fid1.getID()));
            assertFalse(idP1.equals(fid1.getID()));

            // now force the use of provided feature ids
            points1.getUserData().put(Hints.USE_PROVIDED_FID, Boolean.TRUE);
            points2.getUserData().put(Hints.USE_PROVIDED_FID, Boolean.TRUE);
            points3.getUserData().put(Hints.USE_PROVIDED_FID, Boolean.TRUE);

            List<FeatureId> providedFids = points.addFeatures(collection);
            assertNotNull(providedFids);
            assertEquals(3, providedFids.size());

            FeatureId fid11 = providedFids.get(0);
            FeatureId fid21 = providedFids.get(1);
            FeatureId fid31 = providedFids.get(2);

            // ids should match provided
            assertEquals(idP1, fid11.getID());
            assertEquals(idP2, fid21.getID());
            assertEquals(idP3, fid31.getID());

            tx.commit();

            assertEquals(1, points.getFeatures(ff.id(Collections.singleton(fid1))).size());
            assertEquals(1, points.getFeatures(ff.id(Collections.singleton(fid2))).size());
            assertEquals(1, points.getFeatures(ff.id(Collections.singleton(fid3))).size());

            assertEquals(1, points.getFeatures(ff.id(Collections.singleton(fid11))).size());
            assertEquals(1, points.getFeatures(ff.id(Collections.singleton(fid21))).size());
            assertEquals(1, points.getFeatures(ff.id(Collections.singleton(fid31))).size());

        } catch (Exception e) {
            tx.rollback();
            throw e;
        } finally {
            tx.close();
        }
    }

    @Test
    public void testModifyFeatures() throws Exception {
        // add features circumventing FeatureStore.addFeatures to keep the test
        // independent of the addFeatures functionality
        insertAndAdd(lines1, lines2, lines3, points1, points2, points3);
        geogit.command(CommitOp.class).call();

        Id filter = ff.id(Collections.singleton(ff.featureId(idP1)));
        Transaction tx = new DefaultTransaction();
        points.setTransaction(tx);
        try {
            // initial value
            SimpleFeature initial = points.getFeatures(filter).features().next();
            assertEquals("StringProp1_1", initial.getAttribute("sp"));

            // modify
            points.modifyFeatures("sp", "modified", filter);

            // modified value before commit
            SimpleFeature modified = points.getFeatures(filter).features().next();
            assertEquals("modified", modified.getAttribute("sp"));

            // unmodified value before commit on another store instance (tx isolation)
            assertEquals("StringProp1_1",
                    dataStore.getFeatureSource(pointsTypeName).getFeatures(filter).features()
                            .next().getAttribute("sp"));

            tx.commit();

            // modified value after commit on another store instance
            assertEquals("modified", dataStore.getFeatureSource(pointsTypeName).getFeatures(filter)
                    .features().next().getAttribute("sp"));
        } catch (Exception e) {
            tx.rollback();
            throw e;
        } finally {
            tx.close();
        }
        points.setTransaction(Transaction.AUTO_COMMIT);
        SimpleFeature modified = points.getFeatures(filter).features().next();
        assertEquals("modified", modified.getAttribute("sp"));
    }

    @Test
    public void testRemoveFeatures() throws Exception {
        // add features circumventing FeatureStore.addFeatures to keep the test
        // independent of the
        // addFeatures functionality
        insertAndAdd(lines1, lines2, lines3);
        insertAndAdd(points1, points2, points3);
        geogit.command(CommitOp.class).call();

        Id filter = ff.id(Collections.singleton(ff.featureId(idP1)));
        Transaction tx = new DefaultTransaction();
        points.setTransaction(tx);
        try {
            // initial # of features
            assertEquals(3, points.getFeatures().size());
            // remove feature
            points.removeFeatures(filter);

            // #of features before commit on the same store
            assertEquals(2, points.getFeatures().size());

            // #of features before commit on a different store instance
            assertEquals(3, dataStore.getFeatureSource(pointsTypeName).getFeatures().size());

            tx.commit();

            // #of features after commit on a different store instance
            assertEquals(2, dataStore.getFeatureSource(pointsTypeName).getFeatures().size());
        } catch (Exception e) {
            tx.rollback();
            throw e;
        } finally {
            tx.close();
        }
        points.setTransaction(Transaction.AUTO_COMMIT);
        assertEquals(2, points.getFeatures().size());
        assertEquals(0, points.getFeatures(filter).size());
    }

    @Test
    public void testTransactionCommitMessage() throws Exception {

        FeatureCollection<SimpleFeatureType, SimpleFeature> collection;
        collection = DataUtilities.collection(Arrays.asList((SimpleFeature) points1,
                (SimpleFeature) points2, (SimpleFeature) points3));

        DefaultTransaction tx = new DefaultTransaction();
        points.setTransaction(tx);
        assertSame(tx, points.getTransaction());
        try {
            points.addFeatures(collection);

            tx.putProperty(GeogitTransactionState.VERSIONING_COMMIT_AUTHOR, "John Doe");
            tx.putProperty(GeogitTransactionState.VERSIONING_COMMIT_MESSAGE, "test message");
            tx.commit();
            assertEquals(3, dataStore.getFeatureSource(pointsTypeName).getFeatures().size());
        } catch (Exception e) {
            tx.rollback();
            throw e;
        } finally {
            tx.close();
        }

        List<RevCommit> commits = toList(geogit.command(LogOp.class).call());
        assertFalse(commits.isEmpty());
        assertTrue(commits.get(0).getAuthor().getName().isPresent());
        assertEquals("John Doe", commits.get(0).getAuthor().getName().get());
        assertEquals("test message", commits.get(0).getMessage());
    }

    @Test
    public void testTransactionCommitAuthorAndEmail() throws Exception {

        FeatureCollection<SimpleFeatureType, SimpleFeature> collection;
        collection = DataUtilities.collection(Arrays.asList((SimpleFeature) points1,
                (SimpleFeature) points2, (SimpleFeature) points3));

        DefaultTransaction tx = new DefaultTransaction();
        points.setTransaction(tx);
        assertSame(tx, points.getTransaction());
        try {
            points.addFeatures(collection);

            tx.putProperty(GeogitTransactionState.VERSIONING_COMMIT_AUTHOR, "john");
            tx.putProperty(GeogitTransactionState.VERSIONING_COMMIT_MESSAGE, "test message");
            tx.putProperty("fullname", "John Doe");
            tx.putProperty("email", "jd@example.com");
            tx.commit();
            assertEquals(3, dataStore.getFeatureSource(pointsTypeName).getFeatures().size());
        } catch (Exception e) {
            tx.rollback();
            throw e;
        } finally {
            tx.close();
        }

        List<RevCommit> commits = toList(geogit.command(LogOp.class).call());
        assertFalse(commits.isEmpty());
        assertTrue(commits.get(0).getAuthor().getName().isPresent());
        assertEquals("John Doe", commits.get(0).getAuthor().getName().orNull());
        assertEquals("jd@example.com", commits.get(0).getAuthor().getEmail().orNull());
    }
}