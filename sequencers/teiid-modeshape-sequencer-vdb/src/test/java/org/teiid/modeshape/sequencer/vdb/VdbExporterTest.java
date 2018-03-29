/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.modeshape.sequencer.vdb;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import javax.jcr.Node;
import org.junit.Test;
import org.teiid.modeshape.sequencer.AbstractSequencerTest;
import org.teiid.modeshape.sequencer.Result;
import org.teiid.modeshape.sequencer.vdb.VdbModel.Source;
import org.teiid.modeshape.sequencer.vdb.lexicon.CoreLexicon;

public final class VdbExporterTest extends AbstractSequencerTest {

    private VdbModel findModel( final List< VdbModel > models,
                                final String modelName ) {
        for ( final VdbModel model : models ) {
            if ( modelName.equals( model.getName() ) ) {
                return model;
            }
        }

        fail( "Model " + modelName + " not found" );
        return null;
    }

    @Override
    protected InputStream getRepositoryConfigStream() {
        return resourceStream( "config/repo-config.json" );
    }

    @Test
    public void shouldExportDynamicAzureVdb() throws Exception {
        createNodeWithContentFromFile( "vdb/AzureService-vdb.xml", "vdb/AzureService-vdb.xml" );
        final Node vdbNode = getOutputNode( this.rootNode, "vdbs/AzureService-vdb.xml" );
        assertNotNull( vdbNode );

        final VdbExporter exporter = new VdbExporter();
        final Result result = exporter.execute( vdbNode, null );
        assertThat( result, is( notNullValue() ) );
        assertThat( result.getError(), is( nullValue() ) );
        assertThat( result.getErrorMessage(), is( nullValue() ) );
        assertThat( result.getOutcome(), is( notNullValue() ) );
        assertThat( result.getOutcome(), is( instanceOf( result.getType() ) ) );

        // round trip
        final String xml = ( String )result.getOutcome();
        final VdbManifest manifest = VdbManifest.read( new ByteArrayInputStream( xml.getBytes() ), null );
        assertThat( manifest, is( notNullValue() ) );
        assertThat( manifest.getName(), is( "AzureService" ) );
        assertThat( manifest.getVersion(), is( 1 ) );
        assertThat( manifest.getDescription(), is( "VDB for: AzureService, Version: 1" ) );
        assertThat( manifest.getConnectionType(), is( "BY_VERSION" ) );
        assertThat( manifest.getProperties().size(), is( 2 ) );
        assertThat( manifest.getProperties().get( "{http://teiid.org/rest}auto-generate" ), is( "true" ) );
        assertThat( manifest.getProperties().get( "data-service-view" ), is( "SvcView" ) );
        assertThat( manifest.getImportVdbs().size(), is( 1 ) );
        assertThat( manifest.getModels().size(), is( 1 ) );

        // import VDB
        final ImportVdb importVdb = manifest.getImportVdbs().get( 0 );
        assertThat( importVdb.getName(), is( "SvcSourceVdb_AzurePricesDS" ) );
        assertThat( importVdb.getVersion(), is( 1 ) );
        assertThat( importVdb.isImportDataPolicies(), is( true ) );

        // AzureService model
        final VdbModel azureService = findModel( manifest.getModels(), "AzureService" );
        assertThat( azureService.getType(), is( CoreLexicon.ModelType.VIRTUAL ) );
        assertThat( azureService.isVisible(), is( true ) );
        assertThat( azureService.getDescription(), is( "The Azure Service model" ) );
        assertThat( azureService.getMetadataType(), is( "DDL" ) );
        final String metadata = "CREATE VIEW SvcView (RowId integer PRIMARY KEY, ProdCode string,SalePrice bigdecimal) AS"
                                + " SELECT ROW_NUMBER() OVER (ORDER BY ProdCode) , ProdCode,SalePrice"
                                + " FROM \"Prices.dbo.PricesTable\";" + " SET NAMESPACE 'http://teiid.org/rest' AS REST;\n"
                                + "CREATE VIRTUAL PROCEDURE RestProc () RETURNS (result XML) OPTIONS (\"REST:METHOD\" 'GET', \"REST:URI\" 'rest') AS"
                                + " BEGIN SELECT XMLELEMENT(NAME Elems, XMLAGG(XMLELEMENT(NAME Elem, XMLFOREST(RowId,ProdCode,SalePrice)))) AS result"
                                + " FROM SvcView;" + " END;";
        assertThat( azureService.getModelDefinition(), is( metadata ) );
    }

    @Test
    public void shouldExportDynamicProductVdb() throws Exception {
        createNodeWithContentFromFile( "vdb/product-view-vdb.xml", "vdb/product-view-vdb.xml" );
        final Node vdbNode = getOutputNode( this.rootNode, "vdbs/product-view-vdb.xml" );
        assertNotNull( vdbNode );

        final VdbExporter exporter = new VdbExporter();
        final Result result = exporter.execute( vdbNode, null );
        assertThat( result, is( notNullValue() ) );
        assertThat( result.getError(), is( nullValue() ) );
        assertThat( result.getErrorMessage(), is( nullValue() ) );
        assertThat( result.getOutcome(), is( notNullValue() ) );
        assertThat( result.getOutcome(), is( instanceOf( result.getType() ) ) );

        // round trip
        final String xml = ( String )result.getOutcome();
        final VdbManifest manifest = VdbManifest.read( new ByteArrayInputStream( xml.getBytes() ), null );
        assertThat( manifest, is( notNullValue() ) );
        assertThat( manifest.getName(), is( "DynamicProducts" ) );
        assertThat( manifest.getVersion(), is( 2 ) );
        assertThat( manifest.getDescription(), is( "Product Dynamic VDB" ) );
        assertThat( manifest.getProperties().size(), is( 1 ) );
        assertThat( manifest.getProperties().get( "UseConnectorMetadata" ), is( "true" ) );
        assertThat( manifest.getModels().size(), is( 3 ) );

        { // products model
            final VdbModel products = findModel( manifest.getModels(), "ProductsMySQL_Dynamic" );
            assertThat( products.getType(), is( CoreLexicon.ModelType.PHYSICAL ) );
            assertThat( products.getSources().size(), is( 1 ) );

            final Source source = products.getSources().get( 0 );
            assertThat( source.getName(), is( "jdbc" ) );
            assertThat( source.getTranslator(), is( "mysql" ) );
            assertThat( source.getJndiName(), is( "java:/ProductsMySQL" ) );

            //
            // Look at the product model properties and check that
            // there is a property for the origin connection of the model source
            //
            Map<String, String> productProps = products.getProperties();
            assertTrue( productProps.size() == 1);
            String propName = VdbModel.createOriginConnectionPropertyName(source.getName());
            assertTrue( productProps.containsKey(propName));
            assertThat( productProps.get(propName), is("ProductsMySQL" ));
        }

        { // productView model
            final VdbModel productView = findModel( manifest.getModels(), "ProductViews" );
            assertThat( productView.getType(), is( CoreLexicon.ModelType.VIRTUAL ) );
            assertThat( productView.getMetadataType(), is( "DDL" ) );
            final String metadata = "CREATE VIEW PRODUCT_VIEW ( ID string, name string, type string"
                                    + " ) AS SELECT INSTR_ID AS ID, NAME, TYPE"
                                    + " FROM ProductsMySQL_Dynamic.PRODUCTS.PRODUCTDATA;";
            assertThat( productView.getModelDefinition(), is( metadata ) );
        }

        { // productSummary model
            final VdbModel productSummary = findModel( manifest.getModels(), "ProductSummary" );
            assertThat( productSummary.getType(), is( CoreLexicon.ModelType.VIRTUAL ) );
            assertThat( productSummary.getMetadataType(), is( "DDL" ) );
            final String metadata = "CREATE VIEW PRODUCT_SUMMARY ( ID string, name string, type string"
                                    + " ) AS SELECT INSTR_ID AS ID, NAME, TYPE"
                                    + " FROM ProductsMySQL_Dynamic.PRODUCTS.PRODUCTDATA;";
            assertThat( productSummary.getModelDefinition(), is( metadata ) );
        }
    }

    @Test
    public void shouldExportDynamicTwitterVdb() throws Exception {
        createNodeWithContentFromFile( "vdb/declarativeModels-vdb.xml", "vdb/declarativeModels-vdb.xml" );
        final Node vdbNode = getOutputNode( this.rootNode, "vdbs/declarativeModels-vdb.xml" );
        assertNotNull( vdbNode );

        final VdbExporter exporter = new VdbExporter();
        final Result result = exporter.execute( vdbNode, null );
        assertThat( result, is( notNullValue() ) );
        assertThat( result.getError(), is( nullValue() ) );
        assertThat( result.getErrorMessage(), is( nullValue() ) );
        assertThat( result.getOutcome(), is( notNullValue() ) );
        assertThat( result.getOutcome(), is( instanceOf( result.getType() ) ) );

        // round trip
        final String xml = ( String )result.getOutcome();
        final VdbManifest manifest = VdbManifest.read( new ByteArrayInputStream( xml.getBytes() ), null );
        assertThat( manifest, is( notNullValue() ) );
        assertThat( manifest.getName(), is( "twitter" ) );
        assertThat( manifest.getVersion(), is( 1 ) );
        assertThat( manifest.getDescription(), is( "Shows how to call Web Services" ) );
        assertThat( manifest.getProperties().size(), is( 1 ) );
        assertThat( manifest.getProperties().get( "UseConnectorMetadata" ), is( "cached" ) );
        assertThat( manifest.getModels().size(), is( 2 ) );

        { // twitter model
            final VdbModel twitter = findModel( manifest.getModels(), "twitter" );
            assertThat( twitter.getType(), is( CoreLexicon.ModelType.PHYSICAL ) );
            assertThat( twitter.getSources().size(), is( 1 ) );

            final Source source = twitter.getSources().get( 0 );
            assertThat( source.getName(), is( "twitter" ) );
            assertThat( source.getTranslator(), is( "rest" ) );
            assertThat( source.getJndiName(), is( "java:/twitterDS" ) );
        }

        { // twitterview model
            final VdbModel twitterview = findModel( manifest.getModels(), "twitterview" );
            assertThat( twitterview.getType(), is( CoreLexicon.ModelType.VIRTUAL ) );
            assertThat( twitterview.getMetadataType(), is( "DDL" ) );
            final String metadata = "CREATE VIRTUAL PROCEDURE getTweets(query varchar) RETURNS (created_on varchar(25),"
                                    + " from_user varchar(25), to_user varchar(25),"
                                    + " profile_image_url varchar(25), source varchar(25), text varchar(140)) AS"
                                    + " select tweet.* from"
                                    + " (call twitter.invokeHTTP(action => 'GET', endpoint =>querystring('',query as \"q\"))) w,"
                                    + " XMLTABLE('results' passing JSONTOXML('myxml', w.result) columns"
                                    + " created_on string PATH 'created_at'," + " from_user string PATH 'from_user',"
                                    + " to_user string PATH 'to_user'," + " profile_image_url string PATH 'profile_image_url',"
                                    + " source string PATH 'source'," + " text string PATH 'text') tweet;"
                                    + " CREATE VIEW Tweet AS select * FROM twitterview.getTweets;";
            assertThat( twitterview.getModelDefinition(), is( metadata ) );
        }

        // translator
        assertThat( manifest.getTranslators().size(), is( 1 ) );
        final VdbTranslator translator = manifest.getTranslators().get( 0 );
        assertThat( translator.getName(), is( "rest" ) );
        assertThat( translator.getType(), is( "ws" ) );
        assertThat( translator.getProperties().size(), is( 2 ) );
        assertThat( translator.getProperties().get( "DefaultBinding" ), is( "HTTP" ) );
        assertThat( translator.getProperties().get( "DefaultServiceMode" ), is( "MESSAGE" ) );
    }

}
