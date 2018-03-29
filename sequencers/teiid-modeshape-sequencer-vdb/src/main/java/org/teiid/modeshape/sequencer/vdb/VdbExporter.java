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

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.modeshape.common.logging.Logger;
import org.modeshape.common.util.StringUtil;
import org.teiid.modeshape.sequencer.Options;
import org.teiid.modeshape.sequencer.internal.AbstractExporter;
import org.teiid.modeshape.sequencer.vdb.VdbDataRole.Condition;
import org.teiid.modeshape.sequencer.vdb.VdbDataRole.Mask;
import org.teiid.modeshape.sequencer.vdb.VdbDataRole.Permission;
import org.teiid.modeshape.sequencer.vdb.VdbModel.Source;
import org.teiid.modeshape.sequencer.vdb.VdbModel.ValidationMarker;
import org.teiid.modeshape.sequencer.vdb.lexicon.CoreLexicon;
import org.teiid.modeshape.sequencer.vdb.lexicon.VdbLexicon;
import org.teiid.modeshape.sequencer.vdb.lexicon.VdbLexicon.DataRole;

/**
 * An exporter for VDBs.
 */
public class VdbExporter extends AbstractExporter {

    /**
     * First param is child node type. Second param is the parent node path. Third param is child node name.
     */
    private static final String FIND_CHILD_BY_NAME_AND_TYPE = "SELECT [jcr:path] FROM ['%s'] WHERE ISCHILDNODE('%s') AND [jcr:name] = '%s' LIMIT 1";

    /**
     * First param is child node type. Second param is the parent node path.
     */
    private static final String FIND_CHILD_WITH_TYPE = "SELECT [jcr:path] FROM ['%s'] WHERE ISCHILDNODE('%s')";

    private static final Logger LOGGER = Logger.getLogger( VdbExporter.class );

    private VdbManifest constructManifest( final Node vdb,
                                           final Options options ) throws Exception {

        VdbManifest manifest = null;

        { // name is required
            String name = null;

            if ( vdb.hasProperty( VdbLexicon.Vdb.NAME ) ) {
                name = vdb.getProperty( VdbLexicon.Vdb.NAME ).getString();
            } else {
                name = vdb.getName();
            }

            manifest = new VdbManifest( name );
        }

        // description
        if ( vdb.hasProperty( VdbLexicon.Vdb.DESCRIPTION ) ) {
            manifest.setDescription( vdb.getProperty( VdbLexicon.Vdb.DESCRIPTION ).getString() );
        }

        // version
        if ( vdb.hasProperty( VdbLexicon.Vdb.VERSION ) ) {
            manifest.setVersion( ( int )vdb.getProperty( VdbLexicon.Vdb.VERSION ).getLong() );
        }

        // connection type
        if ( vdb.hasProperty( VdbLexicon.Vdb.CONNECTION_TYPE ) ) {
            manifest.setConnectionType( vdb.getProperty( VdbLexicon.Vdb.CONNECTION_TYPE ).getString() );
        }

        { // properties
            final Map< String, String > props = manifest.getProperties();
            final Options.PropertyFilter filter = getPropertyFilter( options );
            final PropertyIterator itr = vdb.getProperties();

            while ( itr.hasNext() ) {
                final Property property = itr.nextProperty();
                final String propName = property.getName();

                if ( filter.accept( propName ) ) {
                    String name = propName;
                    final int index = name.indexOf( ':' );

                    if ( index != -1 ) {
                        final String uri = vdb.getSession().getNamespaceURI( name.substring( 0, index ) );
                        name = '{'
                               + uri
                               + '}'
                               + name.substring( index
                                                 + 1 );
                    }

                    props.put( name, property.getValue().getString() );
                }
            }
        }

        { // models
            final Node[] modelNodes = findChildrenWithType( vdb, VdbLexicon.Vdb.DECLARATIVE_MODEL );

            if ( modelNodes.length != 0 ) {
                final List< VdbModel > models = manifest.getModels();

                for ( final Node modelNode : modelNodes ) {
                    final String type = modelNode.getProperty( CoreLexicon.JcrId.MODEL_TYPE ).getString();
                    String pathInVdb = null;

                    if ( modelNode.hasProperty( VdbLexicon.Model.PATH_IN_VDB ) ) {
                        pathInVdb = modelNode.getProperty( VdbLexicon.Model.PATH_IN_VDB ).getString();
                    }

                    final VdbModel model = new VdbModel( modelNode.getName(), type, pathInVdb );

                    // visible
                    if ( modelNode.hasProperty( VdbLexicon.Model.VISIBLE ) ) {
                        model.setVisible( modelNode.getProperty( VdbLexicon.Model.VISIBLE ).getBoolean() );
                    }

                    // description
                    if ( modelNode.hasProperty( VdbLexicon.Model.DESCRIPTION ) ) {
                        model.setDescription( modelNode.getProperty( VdbLexicon.Model.DESCRIPTION ).getString() );
                    }

                    { // properties
                        final Map< String, String > props = model.getProperties();
                        final Options.PropertyFilter filter = getPropertyFilter( options );
                        final PropertyIterator itr = modelNode.getProperties();

                        while ( itr.hasNext() ) {
                            final Property property = itr.nextProperty();

                            if ( filter.accept( property.getName() ) ) {
                                props.put( property.getName(), property.getValue().getString() );
                            }
                        }
                    }

                    // metadata type
                    if ( modelNode.hasProperty( VdbLexicon.Model.METADATA_TYPE ) ) {
                        model.setMetadataType( modelNode.getProperty( VdbLexicon.Model.METADATA_TYPE ).getString() );
                    }

                    // model definition
                    if ( modelNode.hasProperty( VdbLexicon.Model.MODEL_DEFINITION ) ) {
                        model.setModelDefinition( modelNode.getProperty( VdbLexicon.Model.MODEL_DEFINITION ).getString() );
                    }

                    // DDL file
                    if ( modelNode.hasProperty( VdbLexicon.Model.DDL_FILE_ENTRY_PATH ) ) {
                        model.setDdlFileEntryPath( modelNode.getProperty( VdbLexicon.Model.DDL_FILE_ENTRY_PATH ).getString() );
                    }

                    { // sources
                        final Node groupingNode = findNodeByNameAndType( modelNode,
                                                                         VdbLexicon.Vdb.SOURCES,
                                                                         VdbLexicon.Vdb.SOURCES );

                        if ( groupingNode != null ) {
                            final Node[] sourceNodes = findChildrenWithType( groupingNode, VdbLexicon.Source.SOURCE );

                            if ( sourceNodes.length != 0 ) {
                                final List< Source > sources = model.getSources();

                                for ( final Node sourceNode : sourceNodes ) {
                                    final String translator = sourceNode.getProperty( VdbLexicon.Source.TRANSLATOR ).getString();
                                    final Source source = model.new Source( sourceNode.getName(), translator );

                                    if ( sourceNode.hasProperty( VdbLexicon.Source.JNDI_NAME ) ) {
                                        final String jndiName = sourceNode.getProperty( VdbLexicon.Source.JNDI_NAME ).getString();
                                        source.setJndiName( jndiName );
                                    }

                                    /*
                                     * If the source node has an origin connection property then it denotes
                                     * the connection that the model source is based on. This has to be preserved
                                     * in the parent model properties.
                                     */
                                    if (sourceNode.hasProperty(VdbLexicon.Source.ORIGIN_CONNECTION)) {
                                        String connName = sourceNode.getProperty(VdbLexicon.Source.ORIGIN_CONNECTION).getString();
                                        if (connName != null) {
                                            /*
                                             * Add a property to the model that specifies the association
                                             * between the named source and the named connection
                                             */
                                            model.getProperties().put(VdbModel.createOriginConnectionPropertyName(source.getName()), connName);
                                        }
                                    }

                                    sources.add( source );
                                    LOGGER.debug( "Added source {0} to model {1}", source.getName(), model.getName() );
                                }
                            }
                        }
                    }

                    models.add( model );
                    LOGGER.debug( "Added model {0} to manifest", model.getName() );
                }
            }
        }

        { // translators
            final Node groupingNode = findNodeByNameAndType( vdb, VdbLexicon.Vdb.TRANSLATORS, VdbLexicon.Vdb.TRANSLATORS );

            if ( groupingNode != null ) {
                final Node[] translatorNodes = findChildrenWithType( groupingNode, VdbLexicon.Translator.TRANSLATOR );

                if ( translatorNodes.length != 0 ) {
                    final List< VdbTranslator > translators = manifest.getTranslators();

                    for ( final Node translatorNode : translatorNodes ) {
                        final String type = translatorNode.getProperty( VdbLexicon.Translator.TYPE ).getString();
                        final VdbTranslator translator = new VdbTranslator( translatorNode.getName(), type );

                        if ( translatorNode.hasProperty( VdbLexicon.Translator.DESCRIPTION ) ) {
                            final String description = translatorNode.getProperty( VdbLexicon.Translator.DESCRIPTION ).getString();
                            translator.setDescription( description );
                        }

                        { // properties
                            final Map< String, String > props = translator.getProperties();
                            final Options.PropertyFilter filter = getPropertyFilter( options );
                            final PropertyIterator itr = translatorNode.getProperties();

                            while ( itr.hasNext() ) {
                                final Property property = itr.nextProperty();

                                if ( filter.accept( property.getName() ) ) {
                                    props.put( property.getName(), property.getValue().getString() );
                                }
                            }
                        }

                        translators.add( translator );
                        LOGGER.debug( "Added translator {0} to manifest", translator.getName() );
                    }
                }
            }
        }

        { // data roles
            final Node groupingNode = findNodeByNameAndType( vdb, VdbLexicon.Vdb.DATA_ROLES, VdbLexicon.Vdb.DATA_ROLES );

            if ( groupingNode != null ) {
                final Node[] dataRoleNodes = findChildrenWithType( groupingNode, DataRole.DATA_ROLE );

                if ( dataRoleNodes.length != 0 ) {
                    final List< VdbDataRole > dataRoles = manifest.getDataRoles();

                    for ( final Node dataRoleNode : dataRoleNodes ) {
                        final VdbDataRole dataRole = new VdbDataRole( dataRoleNode.getName() );

                        if ( dataRoleNode.hasProperty( VdbLexicon.DataRole.DESCRIPTION ) ) {
                            final String description = dataRoleNode.getProperty( VdbLexicon.DataRole.DESCRIPTION ).getString();
                            dataRole.setDescription( description );
                        }

                        dataRole.setAnyAuthenticated( dataRoleNode.getProperty( VdbLexicon.DataRole.ANY_AUTHENTICATED ).getBoolean() );
                        dataRole.setAllowCreateTempTables( dataRoleNode.getProperty( VdbLexicon.DataRole.ALLOW_CREATE_TEMP_TABLES ).getBoolean() );
                        dataRole.setGrantAll( dataRoleNode.getProperty( VdbLexicon.DataRole.GRANT_ALL ).getBoolean() );

                        if ( dataRoleNode.hasProperty( VdbLexicon.DataRole.MAPPED_ROLE_NAMES ) ) {
                            final List< String > mappedRoles = dataRole.getMappedRoleNames();

                            for ( final Value value : dataRoleNode.getProperty( VdbLexicon.DataRole.MAPPED_ROLE_NAMES ).getValues() ) {
                                final String mappedRole = value.getString();
                                mappedRoles.add( mappedRole );
                                LOGGER.debug( "Added mapped role {0} to data role {1}", mappedRole, dataRole.getName() );
                            }
                        }

                        { // permissions
                            final Node permissionsGroupingNode = findNodeByNameAndType( dataRoleNode,
                                                                                        VdbLexicon.DataRole.PERMISSIONS,
                                                                                        VdbLexicon.DataRole.PERMISSIONS );

                            if ( permissionsGroupingNode != null ) {
                                final Node[] permissionNodes = findChildrenWithType( permissionsGroupingNode,
                                                                                     org.teiid.modeshape.sequencer.vdb.lexicon.VdbLexicon.DataRole.Permission.PERMISSION );

                                if ( permissionNodes.length != 0 ) {
                                    final List< Permission > permissions = dataRole.getPermissions();

                                    for ( final Node permissionNode : permissionNodes ) {
                                        final Permission permission = dataRole.new Permission( dataRoleNode.getName() );
                                        permission.allowAlter( permissionNode.getProperty( VdbLexicon.DataRole.Permission.ALLOW_ALTER ).getBoolean() );
                                        permission.allowCreate( permissionNode.getProperty( VdbLexicon.DataRole.Permission.ALLOW_CREATE ).getBoolean() );
                                        permission.allowDelete( permissionNode.getProperty( VdbLexicon.DataRole.Permission.ALLOW_DELETE ).getBoolean() );
                                        permission.allowExecute( permissionNode.getProperty( VdbLexicon.DataRole.Permission.ALLOW_EXECUTE ).getBoolean() );
                                        permission.allowLanguage( permissionNode.getProperty( VdbLexicon.DataRole.Permission.ALLOW_LANGUAGE ).getBoolean() );
                                        permission.allowRead( permissionNode.getProperty( VdbLexicon.DataRole.Permission.ALLOW_READ ).getBoolean() );
                                        permission.allowUpdate( permissionNode.getProperty( VdbLexicon.DataRole.Permission.ALLOW_UPDATE ).getBoolean() );

                                        { // conditions
                                            final Node[] conditionNodes = findChildrenWithType( permissionNode,
                                                                                                VdbLexicon.DataRole.Permission.Condition.CONDITION );

                                            if ( conditionNodes.length != 0 ) {
                                                final List< Condition > conditions = permission.getConditions();

                                                for ( final Node conditionNode : conditionNodes ) {
                                                    final Condition condition = dataRole.new Condition();
                                                    condition.setConstraint( conditionNode.getProperty( VdbLexicon.DataRole.Permission.Condition.CONSTRAINT ).getBoolean() );
                                                    condition.setRule( conditionNode.getName() );
                                                    conditions.add( condition );
                                                    LOGGER.debug( "Added condition {0} to permission {1}",
                                                                  condition.getRule(),
                                                                  permission.getResourceName() );
                                                }
                                            }
                                        }

                                        { // masks
                                            final Node[] maskNodes = findChildrenWithType( permissionNode,
                                                                                           VdbLexicon.DataRole.Permission.Mask.MASK );

                                            if ( maskNodes.length != 0 ) {
                                                final List< Mask > masks = permission.getMasks();

                                                for ( final Node maskNode : maskNodes ) {
                                                    final Mask mask = dataRole.new Mask();
                                                    mask.setOrder( ( int )maskNode.getProperty( VdbLexicon.DataRole.Permission.Mask.ORDER ).getLong() );
                                                    mask.setRule( maskNode.getName() );
                                                    masks.add( mask );
                                                    LOGGER.debug( "Added mask {0} to permission {1}",
                                                                  mask.getRule(),
                                                                  permission.getResourceName() );
                                                }
                                            }
                                        }

                                        permissions.add( permission );
                                        LOGGER.debug( "Added permission {0} to data role {1}",
                                                      permission.getResourceName(),
                                                      dataRole.getName() );
                                    }
                                }
                            }
                        }

                        dataRoles.add( dataRole );
                        LOGGER.debug( "Added data role {0} to manifest", dataRole.getName() );
                    }
                }
            }
        }

        { // entries
            final Node groupingNode = findNodeByNameAndType( vdb, VdbLexicon.Vdb.ENTRIES, VdbLexicon.Vdb.ENTRIES );

            if ( groupingNode != null ) {
                final Node[] entryNodes = findChildrenWithType( groupingNode, VdbLexicon.Entry.ENTRY );

                if ( entryNodes.length != 0 ) {
                    final List< VdbEntry > entries = manifest.getEntries();

                    for ( final Node entryNode : entryNodes ) {
                        final String path = entryNode.getProperty( VdbLexicon.Entry.PATH ).getString();
                        final VdbEntry entry = new VdbEntry( path );

                        if ( entryNode.hasProperty( VdbLexicon.Entry.DESCRIPTION ) ) {
                            final String description = entryNode.getProperty( VdbLexicon.Entry.DESCRIPTION ).getString();
                            entry.setDescription( description );
                        }

                        { // properties
                            final Map< String, String > props = entry.getProperties();
                            final Options.PropertyFilter filter = getPropertyFilter( options );
                            final PropertyIterator itr = entryNode.getProperties();

                            while ( itr.hasNext() ) {
                                final Property property = itr.nextProperty();

                                if ( filter.accept( property.getName() ) ) {
                                    props.put( property.getName(), property.getValue().getString() );
                                }
                            }
                        }

                        entries.add( entry );
                        LOGGER.debug( "Added entry with path {0} to manifest", entry.getPath() );
                    }
                }
            }
        }

        { // import VDBs
            final Node groupingNode = findNodeByNameAndType( vdb, VdbLexicon.Vdb.IMPORT_VDBS, VdbLexicon.Vdb.IMPORT_VDBS );

            if ( groupingNode != null ) {
                final Node[] children = findChildrenWithType( groupingNode, VdbLexicon.ImportVdb.IMPORT_VDB );

                if ( children.length != 0 ) {
                    final List< ImportVdb > importVdbs = manifest.getImportVdbs();

                    for ( final Node node : children ) {
                        final int version = Integer.parseInt( node.getProperty( VdbLexicon.ImportVdb.VERSION ).getString() );
                        final ImportVdb importVdb = new ImportVdb( node.getName(), version );

                        final boolean importDataPolicies = node.getProperty( VdbLexicon.ImportVdb.IMPORT_DATA_POLICIES ).getBoolean();
                        importVdb.setImportDataPolicies( importDataPolicies );

                        importVdbs.add( importVdb );
                        LOGGER.debug( "Added import VDB {0} to manifest", importVdb.getName() );
                    }
                }
            }
        }

        // NOTE: vdb:resource children cannot be exported in the manifest

        return manifest;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.teiid.modeshape.sequencer.internal.AbstractExporter#doExport(javax.jcr.Node,
     *      org.teiid.modeshape.sequencer.Options, org.teiid.modeshape.sequencer.internal.AbstractExporter.ResultImpl)
     */
    @Override
    protected void doExport( final Node vdbNode,
                             final Options options,
                             final ResultImpl result ) {
        XMLStreamWriter xmlWriter = null;

        try {
            final VdbManifest manifest = constructManifest( vdbNode, options );
            final StringWriter stringWriter = new StringWriter();
            final XMLOutputFactory xof = XMLOutputFactory.newInstance();
            xmlWriter = xof.createXMLStreamWriter( stringWriter );
            xmlWriter.writeStartDocument( "UTF-8", "1.0" );

            // root element
            xmlWriter.writeStartElement( VdbLexicon.ManifestIds.VDB );
            xmlWriter.writeAttribute( VdbLexicon.ManifestIds.NAME, manifest.getName() );
            xmlWriter.writeAttribute( VdbLexicon.ManifestIds.VERSION, Integer.toString( manifest.getVersion() ) );

            // VDB description element is optional
            if ( !StringUtil.isBlank( manifest.getDescription() ) ) {
                xmlWriter.writeStartElement( VdbLexicon.ManifestIds.DESCRIPTION );
                xmlWriter.writeCharacters( manifest.getDescription() );
                xmlWriter.writeEndElement();
            }

            // VDB connection type element is optional
            if ( !StringUtil.isBlank( manifest.getConnectionType() ) ) {
                xmlWriter.writeStartElement( VdbLexicon.ManifestIds.CONNECTION_TYPE );
                xmlWriter.writeCharacters( manifest.getConnectionType() );
                xmlWriter.writeEndElement();
            }

            { // properties are optional
                final Map< String, String > props = manifest.getProperties();

                if ( !props.isEmpty() ) {
                    for ( final Entry< String, String > property : props.entrySet() ) {
                        writePropertyElement( xmlWriter, property.getKey(), property.getValue() );
                    }
                }
            }

            { // import-vdb collection is optional
                final List< ImportVdb > imports = manifest.getImportVdbs();

                if ( !imports.isEmpty() ) {
                    for ( final ImportVdb importVdb : imports ) {
                        xmlWriter.writeStartElement( VdbLexicon.ManifestIds.IMPORT_VDB );
                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.IMPORT_DATA_POLICIES,
                                                  Boolean.toString( importVdb.isImportDataPolicies() ) );
                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.NAME, importVdb.getName() );
                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.VERSION, Integer.toString( importVdb.getVersion() ) );
                        xmlWriter.writeEndElement();
                    }
                }
            }

            { // model collection is optional
                final List< VdbModel > models = manifest.getModels();

                if ( !models.isEmpty() ) {
                    for ( final VdbModel model : models ) {
                        xmlWriter.writeStartElement( VdbLexicon.ManifestIds.MODEL );

                        // name attribute
                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.NAME, model.getName() );

                        // model type attribute
                        if ( ( model.getType() != null )
                             && !model.getType().equals( VdbModel.DEFAULT_MODEL_TYPE ) ) {
                            xmlWriter.writeAttribute( VdbLexicon.ManifestIds.TYPE, model.getType() );
                        }

                        // visible attribute
                        if ( model.isVisible() != VdbModel.DEFAULT_VISIBLE ) {
                            xmlWriter.writeAttribute( VdbLexicon.ManifestIds.VISIBLE, Boolean.toString( model.isVisible() ) );
                        }

                        if ( !StringUtil.isBlank( model.getPathInVdb() ) ) {
                            xmlWriter.writeAttribute( VdbLexicon.ManifestIds.PATH, model.getPathInVdb() );
                        }

                        // model description element is optional
                        if ( !StringUtil.isBlank( model.getDescription() ) ) {
                            xmlWriter.writeStartElement( VdbLexicon.ManifestIds.DESCRIPTION );
                            xmlWriter.writeCharacters( model.getDescription() );
                            xmlWriter.writeEndElement();
                        }

                        { // properties are optional
                            final Map< String, String > props = model.getProperties();

                            if ( !props.isEmpty() ) {
                                for ( final Entry< String, String > property : props.entrySet() ) {
                                    writePropertyElement( xmlWriter, property.getKey(), property.getValue() );
                                }
                            }
                        }

                        { // metadata is optional
                          // the VDB schema actually permits more than one metadata entry. We have assumed there to be
                          // only one at this time so that is what is reflected in our design.
                            if ( !StringUtil.isBlank( model.getModelDefinition() ) ) {
                                xmlWriter.writeStartElement( VdbLexicon.ManifestIds.METADATA );

                                if ( !StringUtil.isBlank( model.getMetadataType() ) ) {
                                    xmlWriter.writeAttribute( VdbLexicon.ManifestIds.TYPE, model.getMetadataType() );
                                }

                                // when the model definition was imported and the metadata type was DDL-FILE, the model
                                // definition actually was set using a DDL file contents. On import for this type, the
                                // model definition was the VDB zip archive entry path. Here we are just going to export
                                // the model definition as it stands regardless if it was set using a file. So the
                                // vdb:ddlFileEntryPath property is being ignored.
                                xmlWriter.writeCData( model.getModelDefinition() );
                                xmlWriter.writeEndElement();
                            }
                        }

                        { // model source collection is optional
                            final List< Source > sources = model.getSources();

                            if ( !sources.isEmpty() ) {
                                for ( final Source source : sources ) {
                                    xmlWriter.writeStartElement( VdbLexicon.ManifestIds.SOURCE );
                                    xmlWriter.writeAttribute( VdbLexicon.ManifestIds.NAME, source.getName() );
                                    xmlWriter.writeAttribute( VdbLexicon.ManifestIds.TRANSLATOR_NAME, source.getTranslator() );

                                    if ( !StringUtil.isBlank( source.getJndiName() ) ) {
                                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.JNDI_NAME, source.getJndiName() );
                                    }

                                    xmlWriter.writeEndElement();
                                }
                            }
                        }

                        { // validation error collection is optional
                            final List< ValidationMarker > markers = model.getProblems();

                            if ( !markers.isEmpty() ) {
                                for ( final ValidationMarker marker : markers ) {
                                    xmlWriter.writeStartElement( VdbLexicon.ManifestIds.VALIDATION_ERROR );
                                    xmlWriter.writeAttribute( VdbLexicon.ManifestIds.SEVERITY, marker.getSeverity().name() );

                                    if ( !StringUtil.isBlank( marker.getPath() ) ) {
                                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.PATH, marker.getPath() );
                                    }

                                    xmlWriter.writeCharacters( marker.getMessage() );
                                    xmlWriter.writeEndElement();
                                }
                            }
                        }

                        xmlWriter.writeEndElement();
                    }
                }
            }

            { // translator collection is optional
                final List< VdbTranslator > translators = manifest.getTranslators();

                if ( !translators.isEmpty() ) {
                    for ( final VdbTranslator translator : translators ) {
                        xmlWriter.writeStartElement( VdbLexicon.ManifestIds.TRANSLATOR );
                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.NAME, translator.getName() );
                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.TYPE, translator.getType() );

                        // description is optional
                        if ( !StringUtil.isBlank( translator.getDescription() ) ) {
                            xmlWriter.writeAttribute( VdbLexicon.ManifestIds.DESCRIPTION, translator.getDescription() );
                        }

                        { // translator properties are optional
                            final Map< String, String > props = translator.getProperties();

                            if ( !props.isEmpty() ) {
                                for ( final Entry< String, String > property : props.entrySet() ) {
                                    writePropertyElement( xmlWriter, property.getKey(), property.getValue() );
                                }
                            }
                        }

                        xmlWriter.writeEndElement();
                    }
                }
            }

            { // data-role collection is optional
                final List< VdbDataRole > dataRoles = manifest.getDataRoles();

                if ( !dataRoles.isEmpty() ) {
                    for ( final VdbDataRole dataRole : dataRoles ) {
                        xmlWriter.writeStartElement( VdbLexicon.ManifestIds.DATA_ROLE );

                        // attributes
                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.NAME, dataRole.getName() );
                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.ANY_AUTHENTICATED,
                                                  Boolean.toString( dataRole.isAnyAuthenticated() ) );
                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.ALLOW_CREATE_TEMP_TABLES,
                                                  Boolean.toString( dataRole.isAllowCreateTempTables() ) );
                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.GRANT_ALL, Boolean.toString( dataRole.isGrantAll() ) );

                        // data role description element is optional
                        if ( !StringUtil.isBlank( dataRole.getDescription() ) ) {
                            xmlWriter.writeStartElement( VdbLexicon.ManifestIds.DESCRIPTION );
                            xmlWriter.writeCharacters( dataRole.getDescription() );
                            xmlWriter.writeEndElement();
                        }

                        { // permission elements are optional
                            final List< Permission > permissions = dataRole.getPermissions();

                            if ( !permissions.isEmpty() ) {
                                for ( final Permission permission : permissions ) {
                                    xmlWriter.writeStartElement( VdbLexicon.ManifestIds.PERMISSION );

                                    // resource name element
                                    xmlWriter.writeStartElement( VdbLexicon.ManifestIds.RESOURCE_NAME );
                                    xmlWriter.writeCharacters( permission.getResourceName() );
                                    xmlWriter.writeEndElement();

                                    // allow create
                                    xmlWriter.writeStartElement( VdbLexicon.ManifestIds.ALLOW_CREATE );
                                    xmlWriter.writeCharacters( Boolean.toString( permission.canCreate() ) );
                                    xmlWriter.writeEndElement();

                                    // read
                                    xmlWriter.writeStartElement( VdbLexicon.ManifestIds.ALLOW_READ );
                                    xmlWriter.writeCharacters( Boolean.toString( permission.canRead() ) );
                                    xmlWriter.writeEndElement();

                                    // update
                                    xmlWriter.writeStartElement( VdbLexicon.ManifestIds.ALLOW_UPDATE );
                                    xmlWriter.writeCharacters( Boolean.toString( permission.canUpdate() ) );
                                    xmlWriter.writeEndElement();

                                    // delete
                                    xmlWriter.writeStartElement( VdbLexicon.ManifestIds.ALLOW_DELETE );
                                    xmlWriter.writeCharacters( Boolean.toString( permission.canDelete() ) );
                                    xmlWriter.writeEndElement();

                                    // execute
                                    xmlWriter.writeStartElement( VdbLexicon.ManifestIds.ALLOW_EXECUTE );
                                    xmlWriter.writeCharacters( Boolean.toString( permission.canExecute() ) );
                                    xmlWriter.writeEndElement();

                                    // alter
                                    xmlWriter.writeStartElement( VdbLexicon.ManifestIds.ALLOW_ALTER );
                                    xmlWriter.writeCharacters( Boolean.toString( permission.canAlter() ) );
                                    xmlWriter.writeEndElement();

                                    { // condition elements are optional
                                        final List< Condition > conditions = permission.getConditions();

                                        if ( !conditions.isEmpty() ) {
                                            for ( final Condition condition : conditions ) {
                                                xmlWriter.writeStartElement( VdbLexicon.ManifestIds.CONDITION );
                                                xmlWriter.writeAttribute( VdbLexicon.ManifestIds.CONSTRAINT,
                                                                          Boolean.toString( condition.isConstraint() ) );
                                                xmlWriter.writeCharacters( condition.getRule() );
                                                xmlWriter.writeEndElement();
                                            }
                                        }
                                    }

                                    { // mask elements are optional
                                        final List< Mask > masks = permission.getMasks();

                                        if ( !masks.isEmpty() ) {
                                            for ( final Mask mask : masks ) {
                                                xmlWriter.writeStartElement( VdbLexicon.ManifestIds.MASK );
                                                xmlWriter.writeAttribute( VdbLexicon.ManifestIds.ORDER,
                                                                          Integer.toString( mask.getOrder() ) );
                                                xmlWriter.writeCharacters( mask.getRule() );
                                                xmlWriter.writeEndElement();
                                            }
                                        }
                                    }

                                    xmlWriter.writeEndElement();
                                }
                            }
                        }

                        { // mapped role elements are optional
                            final List< String > mappedRoles = dataRole.getMappedRoleNames();

                            if ( !mappedRoles.isEmpty() ) {
                                for ( final String role : mappedRoles ) {
                                    xmlWriter.writeStartElement( VdbLexicon.ManifestIds.MAPPED_ROLE_NAME );
                                    xmlWriter.writeCharacters( role );
                                    xmlWriter.writeEndElement();
                                }
                            }
                        }

                        xmlWriter.writeEndElement();
                    }
                }
            }

            { // entries collection is optional
                final List< VdbEntry > entries = manifest.getEntries();

                if ( !entries.isEmpty() ) {
                    for ( final VdbEntry entry : entries ) {
                        xmlWriter.writeStartElement( VdbLexicon.ManifestIds.ENTRY );
                        xmlWriter.writeAttribute( VdbLexicon.ManifestIds.PATH, entry.getPath() );

                        // entry description element is optional
                        if ( !StringUtil.isBlank( entry.getDescription() ) ) {
                            xmlWriter.writeStartElement( VdbLexicon.ManifestIds.DESCRIPTION );
                            xmlWriter.writeCharacters( entry.getDescription() );
                            xmlWriter.writeEndElement();
                        }

                        { // entry properties are optional
                            final Map< String, String > props = entry.getProperties();

                            if ( !props.isEmpty() ) {
                                for ( final Entry< String, String > property : props.entrySet() ) {
                                    writePropertyElement( xmlWriter, property.getKey(), property.getValue() );
                                }
                            }
                        }

                        xmlWriter.writeEndElement();
                    }
                }
            }

            xmlWriter.writeEndElement();
            xmlWriter.writeEndDocument();

            final String xml = stringWriter.toString().trim();
            LOGGER.debug( "VDB {0} manifest: \n{1}", vdbNode.getPath(), prettyPrint( xml, options ) );

            final String pretty = ( isPrettyPrint( options ) ? prettyPrint( xml, options ) : xml );
            result.setOutcome( pretty, String.class );
        } catch ( final Exception e ) {
            result.setError( null, e );
        } finally {
            if ( xmlWriter != null ) {
                try {
                    xmlWriter.close();
                } catch ( final Exception e ) {
                    // do nothing
                }
            }
        }
    }

    private Node[] findChildrenWithType( final Node parentNode,
                                         final String childType ) throws Exception {
        final String queryText = String.format( FIND_CHILD_WITH_TYPE, childType, parentNode.getPath() );
        final Session session = parentNode.getSession();
        final QueryManager queryMgr = session.getWorkspace().getQueryManager();
        final Query query = queryMgr.createQuery( queryText, Query.JCR_SQL2 );
        final QueryResult result = query.execute();
        final NodeIterator itr = result.getNodes();

        if ( itr.getSize() == 0 ) {
            return NO_NODES;
        }

        final Node[] children = new Node[ ( int )itr.getSize() ];
        int i = 0;

        while ( itr.hasNext() ) {
            children[ i++ ] = itr.nextNode();
        }

        return children;
    }

    private Node findNodeByNameAndType( final Node parentNode,
                                        final String groupingNodeName,
                                        final String groupingNodeType ) throws Exception {
        final String queryText = String.format( FIND_CHILD_BY_NAME_AND_TYPE,
                                                groupingNodeType,
                                                parentNode.getPath(),
                                                groupingNodeName );
        final Session session = parentNode.getSession();
        final QueryManager queryMgr = session.getWorkspace().getQueryManager();
        final Query query = queryMgr.createQuery( queryText, Query.JCR_SQL2 );
        final QueryResult result = query.execute();
        final NodeIterator itr = result.getNodes();

        if ( itr.getSize() == 0 ) {
            return null;
        }

        return itr.nextNode();
    }

    private void writePropertyElement( final XMLStreamWriter writer,
                                       final String propName,
                                       final String propValue ) throws XMLStreamException {
        writer.writeStartElement( VdbLexicon.ManifestIds.PROPERTY );
        writer.writeAttribute( VdbLexicon.ManifestIds.NAME, propName );
        writer.writeAttribute( VdbLexicon.ManifestIds.VALUE, propValue );
        writer.writeEndElement();
    }

}
