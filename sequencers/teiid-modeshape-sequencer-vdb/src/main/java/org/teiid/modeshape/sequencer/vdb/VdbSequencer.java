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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.jcr.Binary;
import javax.jcr.NamespaceRegistry;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import org.modeshape.common.annotation.ThreadSafe;
import org.modeshape.common.logging.Logger;
import org.modeshape.common.util.CheckArg;
import org.modeshape.common.util.StringUtil;
import org.modeshape.jcr.api.JcrConstants;
import org.modeshape.jcr.api.nodetype.NodeTypeManager;
import org.modeshape.jcr.api.sequencer.Sequencer;
import org.teiid.modeshape.sequencer.vdb.VdbDataRole.Condition;
import org.teiid.modeshape.sequencer.vdb.VdbDataRole.Mask;
import org.teiid.modeshape.sequencer.vdb.VdbDataRole.Permission;
import org.teiid.modeshape.sequencer.vdb.VdbModel.Source;
import org.teiid.modeshape.sequencer.vdb.lexicon.CoreLexicon;
import org.teiid.modeshape.sequencer.vdb.lexicon.VdbLexicon;
import org.teiid.modeshape.sequencer.vdb.model.ModelSequencer;
import org.teiid.modeshape.sequencer.vdb.model.ReferenceResolver;

/**
 * A sequencer of Teiid Virtual Database (VDB) files.
 */
@ThreadSafe
public class VdbSequencer extends Sequencer {

    private static final String DDL_FILE_EXT = ".ddl";
    private static final String LIB_FOLDER = "lib/";
    protected static final Logger LOGGER = Logger.getLogger( VdbSequencer.class );
    private static final String MANIFEST_FILE = "META-INF/vdb.xml";
    private static final Pattern VERSION_REGEX = Pattern.compile( "(.*)[.]\\s*[+-]?([0-9]+)\\s*$" );

    /**
     * Utility method to extract the version information from a VDB filename.
     *
     * @param fileNameWithoutExtension the filename for the VDB, without its extension; may not be null
     * @param version the reference to the AtomicInteger that will be modified to contain the version
     * @return the 'fileNameWithoutExtension' value (without any trailing '.' characters); never null
     */
    public static String extractVersionInformation( String fileNameWithoutExtension,
                                                    final AtomicInteger version ) {
        final Matcher matcher = VERSION_REGEX.matcher( fileNameWithoutExtension );

        if ( matcher.matches() ) {
            // Extract the version number from the name ...
            fileNameWithoutExtension = matcher.group( 1 );
            version.set( Integer.parseInt( matcher.group( 2 ) ) );
        }

        // Remove all trailing '.' characters
        return fileNameWithoutExtension.replaceAll( "[.]*$", "" );
    }

    private ModelSequencer modelSequencer; // constructed during initialize method

    /**
     * @see org.modeshape.jcr.api.sequencer.Sequencer#execute(javax.jcr.Property, javax.jcr.Node,
     *      org.modeshape.jcr.api.sequencer.Sequencer.Context)
     */
    @Override
    public boolean execute( final Property inputProperty,
                            final Node outputNode,
                            final Context context ) throws Exception {
        LOGGER.debug( "VdbSequencer.execute called:outputNode name='{0}', path='{1}'",
                      outputNode.getName(),
                      outputNode.getPath() );

        final Binary binaryValue = inputProperty.getBinary();
        CheckArg.isNotNull( binaryValue, "binary" );

        VdbManifest manifest = null;
        boolean processDdlFiles = false;
        boolean processLibFiles = false;
        final Collection< String > ddlFileModelsFound = new ArrayList<>();

        try ( final ZipInputStream vdbStream = new ZipInputStream( binaryValue.getStream() ) ) {
            ZipEntry entry = null;
            ReferenceResolver resolver = new ReferenceResolver();

            while ( ( entry = vdbStream.getNextEntry() ) != null ) {
                String entryName = entry.getName();

                if ( entryName.endsWith( MANIFEST_FILE ) ) {
                    manifest = readManifest( binaryValue, vdbStream, outputNode, context );
                } else if ( !entry.isDirectory() && this.modelSequencer.hasModelFileExtension( entryName ) ) {
                    LOGGER.debug( "before reading model '{0}'", entryName );

                    // vdb.xml file should be read first in stream so manifest model should be available
                    if ( manifest == null ) {
                        throw new Exception( TeiidI18n.missingVdbManifest.text( outputNode.getPath() ) );
                    }

                    final VdbModel vdbModel = manifest.getModel( entryName );

                    if ( vdbModel == null ) {
                        throw new Exception( TeiidI18n.missingVdbModel.text( entryName, outputNode.getPath() ) );
                    }

                    // call sequencer here after creating node for last part of entry name
                    final int index = entryName.lastIndexOf( '/' ) + 1;

                    if ( ( index != -1 ) && ( index < entryName.length() ) ) {
                        entryName = entryName.substring( index );
                    }

                    final Node modelNode = outputNode.addNode( entryName, VdbLexicon.Vdb.MODEL );
                    final boolean sequenced = this.modelSequencer.sequenceVdbModel( vdbStream,
                                                                                    modelNode,
                                                                                    vdbModel,
                                                                                    resolver,
                                                                                    context );

                    if ( !sequenced ) {
                        modelNode.remove();
                        LOGGER.debug( ">>>>model NOT sequenced '{0}'\n\n", entryName );
                    } else {
                        LOGGER.debug( ">>>>done sequencing model '{0}'\n\n", entryName );
                    }
                } else if ( isDdlFile( entryName ) ) {
                    if ( manifest == null ) {
                        processDdlFiles = true;
                    } else if ( !processDdlFiles ) {
                        final String modelName = sequenceDdlFile( vdbStream, entryName, manifest, outputNode );

                        if ( !StringUtil.isBlank( modelName ) ) {
                            ddlFileModelsFound.add( modelName );
                        }
                    }
                } else if ( !entry.isDirectory() && entryName.startsWith( LIB_FOLDER ) ) {
                    if ( manifest == null ) {
                        processLibFiles = true;
                    } else if ( !processLibFiles ) {
                        sequenceLibResource( vdbStream, entryName, outputNode );
                    }
                } else {
                    LOGGER.debug( "ignoring resource '{0}'", entryName );
                }
            }
        } catch ( final Exception e ) {
            throw new RuntimeException( TeiidI18n.errorReadingVdbFile.text( inputProperty.getPath(), e.getMessage() ), e );
        }

        // make sure there was a manifest
        if ( manifest == null ) {
            throw new Exception( TeiidI18n.missingVdbManifest.text( outputNode.getPath() ) );
        }

        // open zip again to process lib resources and DDL files if necessary
        if ( processLibFiles || processDdlFiles ) {
            LOGGER.debug( "second pass: /lib resources = {0}, DDL files = {1}", processLibFiles, processDdlFiles );

            try ( final ZipInputStream zis = new ZipInputStream( binaryValue.getStream() ) ) {
                ZipEntry entry = null;

                while ( ( entry = zis.getNextEntry() ) != null ) {
                    final String entryName = entry.getName();

                    if ( !entry.isDirectory() ) {
                        if ( processLibFiles && entryName.startsWith( LIB_FOLDER ) ) {
                            sequenceLibResource( zis, entryName, outputNode );
                        } else if ( processDdlFiles && isDdlFile( entryName ) ) {
                            final String modelName = sequenceDdlFile( zis, entryName, manifest, outputNode );

                            if ( !StringUtil.isBlank( modelName ) ) {
                                ddlFileModelsFound.add( modelName );
                            }
                        }
                    }
                }
            }
        }

        // make sure all DDL-FILE models have found there DDL
        for ( final VdbModel model : manifest.getModels() ) {
            if ( VdbModel.DDL_FILE_METADATA_TYPE.equals( model.getMetadataType() )
                 && !ddlFileModelsFound.contains( model.getName() ) ) {
                throw new Exception( TeiidI18n.ddlFileMissing.text( model.getDdlFileEntryPath(), model.getName() ) );
            }
        }

        return true;
    }

    private boolean isDdlFile( final String fileName ) {
        return fileName.endsWith( DDL_FILE_EXT );
    }

    protected VdbManifest readManifest( Binary binaryValue,
                                        InputStream inputStream,
                                        Node outputNode,
                                        Context context ) throws Exception {
        VdbManifest manifest;
        LOGGER.debug( "----before reading vdb.xml" );

        manifest = VdbManifest.read( inputStream, context );
        assert ( manifest != null ) : "manifest is null";

        // Create the output node for the VDB ...
        outputNode.setPrimaryType( VdbLexicon.Vdb.VIRTUAL_DATABASE );
        outputNode.addMixin( JcrConstants.MIX_REFERENCEABLE );
        outputNode.setProperty( VdbLexicon.Vdb.VERSION, manifest.getVersion() );
        outputNode.setProperty( VdbLexicon.Vdb.ORIGINAL_FILE, outputNode.getPath() );

        if ( binaryValue != null ) {
            outputNode.setProperty( JcrConstants.MODE_SHA1, ( ( org.modeshape.jcr.api.Binary )binaryValue ).getHexHash() );
        }

        setProperty( outputNode, VdbLexicon.Vdb.NAME, manifest.getName() );
        setProperty( outputNode, VdbLexicon.Vdb.DESCRIPTION, manifest.getDescription() );
        setProperty( outputNode, VdbLexicon.Vdb.CONNECTION_TYPE, manifest.getConnectionType() );

        // create imported VDBs child nodes
        sequenceImportVdbs( manifest, outputNode );

        // create translator child nodes
        sequenceTranslators( manifest, outputNode );

        // create data role child nodes
        sequenceDataRoles( manifest, outputNode );

        // create entry child nodes
        sequenceEntries( manifest, outputNode );

        // create properties child nodes
        sequenceProperties( manifest, outputNode );

        // create child nodes for declarative models
        sequenceDeclarativeModels( manifest, outputNode );

        LOGGER.debug( ">>>>done reading vdb.xml\n\n" );
        return manifest;
    }

    /**
     * @throws IOException
     * @see org.modeshape.jcr.api.sequencer.Sequencer#initialize(javax.jcr.NamespaceRegistry,
     *      org.modeshape.jcr.api.nodetype.NodeTypeManager)
     */
    @Override
    public void initialize( final NamespaceRegistry registry,
                            final NodeTypeManager nodeTypeManager ) throws RepositoryException, IOException {
        LOGGER.debug( "enter initialize" );

        this.modelSequencer = new ModelSequencer();
        this.modelSequencer.initialize( registry, nodeTypeManager );

        final URL vdbCndUrl = getClass().getResource( "/org/teiid/modeshape/sequencer/vdb/vdb.cnd" );
        registerNodeTypes( vdbCndUrl.openStream(), nodeTypeManager, true );
        LOGGER.debug( "vdb.cnd loaded" );

        LOGGER.debug( "exit initialize" );
    }

    /**
     * @param manifest the VDB manifest whose data roles are being sequenced (cannot be <code>null</code>)
     * @param outputNode the VDB node (cannot be <code>null</code>)
     * @throws Exception if an error occurs writing the data roles
     */
    private void sequenceDataRoles( final VdbManifest manifest,
                                    final Node outputNode ) throws Exception {
        assert ( manifest != null ) : "manifest is null";
        assert ( outputNode != null ) : "outputNode is null";

        final List< VdbDataRole > dataRolesGroup = manifest.getDataRoles();

        if ( !dataRolesGroup.isEmpty() ) {
            final Node dataRolesGroupNode = outputNode.addNode( VdbLexicon.Vdb.DATA_ROLES, VdbLexicon.Vdb.DATA_ROLES );

            for ( final VdbDataRole dataRole : dataRolesGroup ) {
                final Node dataRoleNode = dataRolesGroupNode.addNode( dataRole.getName(), VdbLexicon.DataRole.DATA_ROLE );
                setProperty( dataRoleNode, VdbLexicon.DataRole.DESCRIPTION, dataRole.getDescription() );
                dataRoleNode.setProperty( VdbLexicon.DataRole.ANY_AUTHENTICATED, dataRole.isAnyAuthenticated() );
                dataRoleNode.setProperty( VdbLexicon.DataRole.ALLOW_CREATE_TEMP_TABLES, dataRole.isAllowCreateTempTables() );
                dataRoleNode.setProperty( VdbLexicon.DataRole.GRANT_ALL, dataRole.isGrantAll() );

                // set role names
                final List< String > roleNames = dataRole.getMappedRoleNames();

                if ( !roleNames.isEmpty() ) {
                    dataRoleNode.setProperty( VdbLexicon.DataRole.MAPPED_ROLE_NAMES,
                                              roleNames.toArray( new String[ roleNames.size() ] ) );
                }

                // add permissions
                final List< Permission > permissionsGroup = dataRole.getPermissions();

                if ( !permissionsGroup.isEmpty() ) {
                    final Node permissionsGroupNode = dataRoleNode.addNode( VdbLexicon.DataRole.PERMISSIONS,
                                                                            VdbLexicon.DataRole.PERMISSIONS );

                    for ( final Permission permission : permissionsGroup ) {
                        final Node permissionNode = permissionsGroupNode.addNode( permission.getResourceName(),
                                                                                  VdbLexicon.DataRole.Permission.PERMISSION );
                        permissionNode.setProperty( VdbLexicon.DataRole.Permission.ALLOW_ALTER, permission.canAlter() );
                        permissionNode.setProperty( VdbLexicon.DataRole.Permission.ALLOW_CREATE, permission.canCreate() );
                        permissionNode.setProperty( VdbLexicon.DataRole.Permission.ALLOW_DELETE, permission.canDelete() );
                        permissionNode.setProperty( VdbLexicon.DataRole.Permission.ALLOW_EXECUTE, permission.canExecute() );
                        permissionNode.setProperty( VdbLexicon.DataRole.Permission.ALLOW_READ, permission.canRead() );
                        permissionNode.setProperty( VdbLexicon.DataRole.Permission.ALLOW_UPDATE, permission.canUpdate() );
                        permissionNode.setProperty( VdbLexicon.DataRole.Permission.ALLOW_LANGUAGE, permission.useLanguage() );

                        // add permission's conditions
                        List< Condition > conditions = permission.getConditions();
                        if ( !conditions.isEmpty() ) {
                            final Node conditionsGroupNode = permissionNode.addNode( VdbLexicon.DataRole.Permission.CONDITIONS,
                                                                                     VdbLexicon.DataRole.Permission.CONDITIONS );

                            for ( final Condition condition : conditions ) {
                                Node conditionNode = conditionsGroupNode.addNode( condition.getRule(),
                                                                                  VdbLexicon.DataRole.Permission.Condition.CONDITION );
                                conditionNode.setProperty( VdbLexicon.DataRole.Permission.Condition.CONSTRAINT,
                                                           condition.isConstraint() );
                            }
                        }

                        // add permission's masks
                        List< Mask > masks = permission.getMasks();
                        if ( !masks.isEmpty() ) {
                            final Node masksGroupNode = permissionNode.addNode( VdbLexicon.DataRole.Permission.MASKS,
                                                                                VdbLexicon.DataRole.Permission.MASKS );

                            for ( final Mask mask : masks ) {
                                Node maskNode = masksGroupNode.addNode( mask.getRule(),
                                                                        VdbLexicon.DataRole.Permission.Mask.MASK );
                                maskNode.setProperty( VdbLexicon.DataRole.Permission.Mask.ORDER, mask.getOrder() );
                            }
                        }

                    }
                }
            }
        }
    }

    @SuppressWarnings( "resource" )
    private String sequenceDdlFile( final ZipInputStream vdbStream,
                                    final String entryName,
                                    final VdbManifest manifest,
                                    final Node outputNode ) throws Exception {
        LOGGER.debug( "processing DDL file '{0}'", entryName );
        boolean modelFound = false;

        // look for models with DDL-FILE metadata type
        for ( final VdbModel model : manifest.getModels() ) {
            final String metadataType = model.getMetadataType();

            if ( VdbModel.DDL_FILE_METADATA_TYPE.equals( metadataType ) ) {
                String ddlFileEntryPath = model.getDdlFileEntryPath();
                LOGGER.debug( "metadataType='{0}', entryName='{1}', ddlFileEntryPath='{2}'",
                              metadataType,
                              ddlFileEntryPath,
                              entryName );

                if ( !StringUtil.isBlank( ddlFileEntryPath ) && ddlFileEntryPath.startsWith( "/" ) ) {
                    ddlFileEntryPath = ddlFileEntryPath.substring( 1 );
                }

                // if the model DDL entry path property equals the specified entry path set the model definition with the contents
                // of the DDL file
                if ( ddlFileEntryPath.equals( entryName ) ) {
                    final String modelName = model.getName();
                    final NodeIterator itr = outputNode.getNodes( modelName );

                    if ( itr.getSize() != 0 ) {
                        modelFound = true;
                        final Node modelNode = itr.nextNode();
                        final Scanner scanner = new Scanner( vdbStream, "UTF-8" ).useDelimiter( "\\A" );
                        final String ddl = scanner.next().replaceAll( "\\s{2,}", " " ); // collapse whitespace
                        modelNode.setProperty( VdbLexicon.Model.MODEL_DEFINITION, ddl );
                        LOGGER.debug( "Using DDL file content to set model '{0}' metadata to: '{1}'", modelName, ddl );
                        return modelName;
                    }

                    // should not happen
                    throw new Exception( TeiidI18n.missingModelNodeThatReferencesDdlFile.text( modelName, ddlFileEntryPath ) );
                }
            }
        }

        if ( !modelFound ) {
            LOGGER.debug( "DDL file '{0}' was not used in a DDL-FILE model", entryName );
        }

        return null;
    }

    /**
     * @param manifest the VDB manifest whose declarative models are being sequenced (cannot be <code>null</code>)
     * @param outputNode the VDB node (cannot be <code>null</code>)
     * @throws Exception if an error occurs writing the VDB declarative models
     */
    private void sequenceDeclarativeModels( final VdbManifest manifest,
                                            final Node outputNode ) throws Exception {
        assert ( manifest != null ) : "manifest is null";
        assert ( outputNode != null ) : "outputNode is null";

        for ( final VdbModel model : manifest.getModels() ) {
            // if there is metadata then there is no xmi file
            if ( model.isDeclarative() ) {
                LOGGER.debug( ">>>>writing declarative model '{0}'", model.getName() );

                final Node modelNode = outputNode.addNode( model.getName(), VdbLexicon.Vdb.DECLARATIVE_MODEL );

                // set vdb:abstractModel properties
                setProperty( modelNode, VdbLexicon.Model.DESCRIPTION, model.getDescription() );
                modelNode.setProperty( VdbLexicon.Model.VISIBLE, model.isVisible() );
                setProperty( modelNode, VdbLexicon.Model.PATH_IN_VDB, model.getPathInVdb() );

                // set vdb:declarativeModel properties
                setProperty( modelNode, CoreLexicon.JcrId.MODEL_TYPE, model.getType() );
                setProperty( modelNode, VdbLexicon.Model.METADATA_TYPE, model.getMetadataType() );
                setProperty( modelNode, VdbLexicon.Model.MODEL_DEFINITION, model.getModelDefinition() );

                if ( VdbModel.DDL_FILE_METADATA_TYPE.equals( model.getMetadataType() ) ) {
                    setProperty( modelNode, VdbLexicon.Model.DDL_FILE_ENTRY_PATH, model.getDdlFileEntryPath() );
                }

                // set model sources
                List< Source > sources = model.getSources();
                Node modelSourcesGroupNode = null;
                if ( !sources.isEmpty() ) {
                    modelSourcesGroupNode = modelNode.addNode( VdbLexicon.Vdb.SOURCES, VdbLexicon.Vdb.SOURCES );

                    for ( final VdbModel.Source source : sources ) {
                        Node sourceNode = modelSourcesGroupNode.addNode( source.getName(), VdbLexicon.Source.SOURCE );
                        sourceNode.setProperty( VdbLexicon.Source.TRANSLATOR, source.getTranslator() );
                        sourceNode.setProperty( VdbLexicon.Source.JNDI_NAME, source.getJndiName() );
                    }
                }

                for ( Map.Entry< String, String > entry : model.getProperties().entrySet() ) {
                    String key = entry.getKey();
                    if (VdbModel.isSourceFromOriginConnectionProperty(key)) {
                        //
                        // The origin connection property is preserved on the
                        // model but applies to the model source so find the source
                        // and and a property accordingly
                        //
                        if (modelSourcesGroupNode == null)
                            continue;

                        String sourceName = VdbModel.getSourceFromOriginConnectionProperty(key);
                        if (modelSourcesGroupNode.hasNode(sourceName)) {
                            Node sourceNode = modelSourcesGroupNode.getNode(sourceName);
                            //
                            // After parsing and finding the correct source, it is simply a case of applying
                            // the preserved value. What the implementing repository does with this value
                            // is entirely at their discretion.
                            //
                            sourceNode.setProperty(VdbLexicon.Source.ORIGIN_CONNECTION, entry.getValue());
                        }
                    } else {
                        setProperty( modelNode, key, entry.getValue() );
                    }
                }
            }
        }
    }

    /**
     * @param manifest the VDB manifest whose entries are being sequenced (cannot be <code>null</code>)
     * @param outputNode the VDB node (cannot be <code>null</code>)
     * @throws Exception if an error occurs writing the VDB entries
     */
    private void sequenceEntries( final VdbManifest manifest,
                                  final Node outputNode ) throws Exception {
        assert ( manifest != null ) : "manifest is null";
        assert ( outputNode != null ) : "outputNode is null";

        final List< VdbEntry > entriesGroup = manifest.getEntries();

        if ( !entriesGroup.isEmpty() ) {
            final Node entriesGroupNode = outputNode.addNode( VdbLexicon.Vdb.ENTRIES, VdbLexicon.Vdb.ENTRIES );

            for ( final VdbEntry entry : entriesGroup ) {
                final Node entryNode = entriesGroupNode.addNode( VdbLexicon.Entry.ENTRY, VdbLexicon.Entry.ENTRY );
                setProperty( entryNode, VdbLexicon.Entry.PATH, entry.getPath() );
                setProperty( entryNode, VdbLexicon.Entry.DESCRIPTION, entry.getDescription() );

                // add properties
                final Map< String, String > props = entry.getProperties();

                if ( !props.isEmpty() ) {
                    for ( final Map.Entry< String, String > prop : props.entrySet() ) {
                        setProperty( entryNode, prop.getKey(), prop.getValue() );
                    }
                }
            }
        }
    }

    /**
     * @param manifest the VDB manifest whose imported VDBs are being sequenced (cannot be <code>null</code>)
     * @param outputNode the VDB output node where import VDB child nodes will be created (cannot be <code>null</code>)
     * @throws Exception if an error occurs creating nodes or setting properties
     */
    private void sequenceImportVdbs( final VdbManifest manifest,
                                     final Node outputNode ) throws Exception {
        assert ( manifest != null ) : "manifest is null";
        assert ( outputNode != null ) : "outputNode is null";

        final List< ImportVdb > importVdbsGroup = manifest.getImportVdbs();

        if ( !importVdbsGroup.isEmpty() ) {
            final Node importVdbsGroupNode = outputNode.addNode( VdbLexicon.Vdb.IMPORT_VDBS, VdbLexicon.Vdb.IMPORT_VDBS );

            for ( final ImportVdb importVdb : importVdbsGroup ) {
                final Node importVdbNode = importVdbsGroupNode.addNode( importVdb.getName(), VdbLexicon.ImportVdb.IMPORT_VDB );
                importVdbNode.setProperty( VdbLexicon.ImportVdb.VERSION, importVdb.getVersion() );
                importVdbNode.setProperty( VdbLexicon.ImportVdb.IMPORT_DATA_POLICIES, importVdb.isImportDataPolicies() );
            }
        }
    }

    private void sequenceLibResource( final ZipInputStream zis,
                                      final String entryPath,
                                      final Node outputNode ) throws Exception {
        LOGGER.debug( "processing /lib resource '{0}'", entryPath );

        // assumes entry path starts with lib/
        final String resourceName = entryPath.substring( entryPath.lastIndexOf( '/' ) );

        // extract file
        final byte[] buf = new byte[ 1024 ];
        final File file = File.createTempFile( resourceName, null );

        try ( final FileOutputStream fos = new FileOutputStream( file ) ) {
            int numRead = 0;

            while ( ( numRead = zis.read( buf ) ) > 0 ) {
                fos.write( buf, 0, numRead );
            }
        }

        // add under the resources node
        Node resourcesNode = null;

        if ( outputNode.hasNode( VdbLexicon.Vdb.RESOURCES ) ) {
            resourcesNode = outputNode.getNode( VdbLexicon.Vdb.RESOURCES );
        } else {
            resourcesNode = outputNode.addNode( VdbLexicon.Vdb.RESOURCES, VdbLexicon.Vdb.RESOURCES );
        }

        final Node resourceNode = resourcesNode.addNode( resourceName, JcrConstants.NT_FILE );
        final Node contentNode = resourceNode.addNode( JcrConstants.JCR_CONTENT, JcrConstants.NT_RESOURCE );

        // set data property
        final InputStream is = new BufferedInputStream( new FileInputStream( file ) );
        final Binary binary = outputNode.getSession().getValueFactory().createBinary( is );
        contentNode.setProperty( JcrConstants.JCR_DATA, binary );

        // set last modified property
        final Calendar lastModified = Calendar.getInstance();
        lastModified.setTimeInMillis( file.lastModified() );
        contentNode.setProperty( "jcr:lastModified", lastModified );
    }

    /**
     * @param manifest the VDB manifest whose properties are being sequenced (cannot be <code>null</code>)
     * @param outputNode the VDB node where the properties will be added (cannot be <code>null</code>)
     * @throws Exception if an error occurs writing the properties
     */
    private void sequenceProperties( final VdbManifest manifest,
                                     final Node outputNode ) throws Exception {
        assert ( manifest != null ) : "manifest is null";
        assert ( outputNode != null ) : "outputNode is null";

        final Map< String, String > props = manifest.getProperties();

        if ( !props.isEmpty() ) {
            for ( final Map.Entry< String, String > prop : props.entrySet() ) {
                if ( VdbLexicon.ManifestIds.PREVIEW.equals( prop.getKey() ) ) {
                    outputNode.setProperty( VdbLexicon.Vdb.PREVIEW, Boolean.parseBoolean( prop.getValue() ) );
                } else {
                    setProperty( outputNode, prop.getKey(), prop.getValue() );
                }
            }
        }
    }

    /**
     * @param manifest the VDB manifest whose translators are being sequenced (cannot be <code>null</code>)
     * @param outputNode the VDB output node where translators child nodes will be created (cannot be <code>null</code>)
     * @throws Exception if an error occurs creating nodes or setting properties
     */
    private void sequenceTranslators( final VdbManifest manifest,
                                      final Node outputNode ) throws Exception {
        assert ( manifest != null ) : "manifest is null";
        assert ( outputNode != null ) : "outputNode is null";

        final List< VdbTranslator > translatorsGroup = manifest.getTranslators();

        if ( !translatorsGroup.isEmpty() ) {
            final Node translatorsGroupNode = outputNode.addNode( VdbLexicon.Vdb.TRANSLATORS, VdbLexicon.Vdb.TRANSLATORS );

            for ( final VdbTranslator translator : translatorsGroup ) {
                final Node translatorNode = translatorsGroupNode.addNode( translator.getName(),
                                                                          VdbLexicon.Translator.TRANSLATOR );
                setProperty( translatorNode, VdbLexicon.Translator.TYPE, translator.getType() );
                setProperty( translatorNode, VdbLexicon.Translator.DESCRIPTION, translator.getDescription() );

                // add properties
                final Map< String, String > props = translator.getProperties();

                if ( !props.isEmpty() ) {
                    for ( final Map.Entry< String, String > prop : props.entrySet() ) {
                        setProperty( translatorNode, prop.getKey(), prop.getValue() );
                    }
                }
            }
        }
    }

    /**
     * Sets a property value only if the value is not <code>null</code> and not empty.
     *
     * @param node the node whose property is being set (cannot be <code>null</code>)
     * @param name the property name (cannot be <code>null</code>)
     * @param value the property value (can be <code>null</code> or empty)
     * @throws Exception if an error occurs setting the node property
     */
    private void setProperty( final Node node,
                              final String name,
                              final String value ) throws Exception {
        assert ( node != null );
        assert ( !StringUtil.isBlank( name ) );

        if ( !StringUtil.isBlank( value ) ) {
            String propName = name;

            // see if property is prefixed with URI
            if ( propName.startsWith( "{" ) ) {
                final int index = propName.indexOf( "}" );

                if ( index == -1 ) {
                    // TODO
                } else {
                    boolean registered = false;
                    final String uri = propName.substring( 1, index );

                    // if URI is registered then leave name as is
                    for ( final String registeredUri : node.getSession().getWorkspace().getNamespaceRegistry().getURIs() ) {
                        if ( registeredUri.equals( uri ) ) {
                            registered = true;
                            break;
                        }
                    }

                    // if namespace is not registered then register it
                    if ( !registered ) {
                        final NamespaceRegistry registry = node.getSession().getWorkspace().getNamespaceRegistry();
                        final List< String > prefixes = Arrays.asList( registry.getPrefixes() );
                        
                        // need a prefix so use last segment of URI
                        final String[] segments = uri.split( "/" );
                        String prefix = segments[ segments.length - 1 ];
                        int i = 1;
                        
                        while ( prefixes.contains( prefix ) ) {
                            prefix += i;
                        }
                        
                        registry.registerNamespace( prefix, uri );
                    }
                }
            }

            node.setProperty( propName, value );
        }
    }
}
