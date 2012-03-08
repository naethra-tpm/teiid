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
package org.teiid.jboss;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;

import org.jboss.as.naming.deployment.ContextNames;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.ModuleLoader;
import org.jboss.msc.service.AbstractServiceListener;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceBuilder.DependencyType;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceController.State;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.teiid.adminapi.Model;
import org.teiid.adminapi.Translator;
import org.teiid.adminapi.impl.ModelMetaData;
import org.teiid.adminapi.impl.VDBMetaData;
import org.teiid.adminapi.impl.VDBTranslatorMetaData;
import org.teiid.deployers.UDFMetaData;
import org.teiid.deployers.VDBRepository;
import org.teiid.deployers.VDBStatusChecker;
import org.teiid.dqp.internal.datamgr.TranslatorRepository;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.metadata.MetadataRepository;
import org.teiid.metadata.MetadataStore;
import org.teiid.metadata.index.IndexMetadataRepository;
import org.teiid.metadata.index.IndexMetadataStore;
import org.teiid.query.ObjectReplicator;
import org.teiid.query.metadata.DDLMetadataRepository;
import org.teiid.query.metadata.NativeMetadataRepository;
import org.teiid.runtime.RuntimePlugin;
import org.teiid.services.BufferServiceImpl;


class VDBDeployer implements DeploymentUnitProcessor {
	private static final String JAVA_CONTEXT = "java:/"; //$NON-NLS-1$			
	private TranslatorRepository translatorRepository;
	private String asyncThreadPoolName;
	private VDBStatusChecker vdbStatusChecker;
	
	public VDBDeployer (TranslatorRepository translatorRepo, String poolName, VDBStatusChecker vdbStatusChecker) {
		this.translatorRepository = translatorRepo;
		this.asyncThreadPoolName = poolName;
		this.vdbStatusChecker = vdbStatusChecker;
	}
	
	public void deploy(final DeploymentPhaseContext context)  throws DeploymentUnitProcessingException {
		final DeploymentUnit deploymentUnit = context.getDeploymentUnit();
		if (!TeiidAttachments.isVDBDeployment(deploymentUnit)) {
			return;
		}
		final String deploymentName = deploymentUnit.getName();
		final VDBMetaData deployment = deploymentUnit.getAttachment(TeiidAttachments.VDB_METADATA);
		
		// check to see if there is old vdb already deployed.
        final ServiceController<?> controller = context.getServiceRegistry().getService(TeiidServiceNames.vdbServiceName(deployment.getName(), deployment.getVersion()));
        if (controller != null) {
        	LogManager.logInfo(LogConstants.CTX_RUNTIME,  IntegrationPlugin.Util.gs(IntegrationPlugin.Event.TEIID50019, deployment));
            controller.setMode(ServiceController.Mode.REMOVE);
        }
		
		boolean preview = deployment.isPreview();
		if (!preview) {
			List<String> errors = deployment.getValidityErrors();
			if (errors != null && !errors.isEmpty()) {
				throw new DeploymentUnitProcessingException(RuntimePlugin.Util.getString("validity_errors_in_vdb", RuntimePlugin.Event.TEIID40004, deployment)); //$NON-NLS-1$
			}
		}
				
		// add required connector managers; if they are not already there
		for (Translator t: deployment.getOverrideTranslators()) {
			VDBTranslatorMetaData data = (VDBTranslatorMetaData)t;
			
			String type = data.getType();
			Translator parent = this.translatorRepository.getTranslatorMetaData(type);
			if ( parent == null) {				
				throw new DeploymentUnitProcessingException(RuntimePlugin.Util.getString("translator_type_not_found", IntegrationPlugin.Event.TEIID50021, type, deploymentName)); //$NON-NLS-1$
			}
		}
		
		// check if this is a VDB with index files, if there are then build the TransformationMetadata
		UDFMetaData udf = deploymentUnit.removeAttachment(TeiidAttachments.UDF_METADATA);
		if (udf != null) {
			final Module module = deploymentUnit.getAttachment(Attachments.MODULE);
			if (module != null) {
				udf.setFunctionClassLoader(module.getClassLoader());
			}
			deployment.addAttchment(UDFMetaData.class, udf);
		}
		
		// set up the metadata repositories for each models
		IndexMetadataRepository indexRepo = null;
		IndexMetadataStore indexFactory = deploymentUnit.removeAttachment(TeiidAttachments.INDEX_METADATA);
		if (indexFactory != null) {
			indexRepo = new IndexMetadataRepository(indexFactory);
		}

		MetadataStore store = new MetadataStore();	
		deployment.addAttchment(MetadataStore.class, store);
		
		for (ModelMetaData model:deployment.getModelMetaDatas().values()) {
			if (model.isSource() && model.getSourceNames().isEmpty()) {
	    		throw new DeploymentUnitProcessingException(IntegrationPlugin.Util.gs(IntegrationPlugin.Event.TEIID50087, model.getName(), deployment.getName(), deployment.getVersion()));
	    	}
			MetadataRepository repo = getMetadataRepository(deployment, model.getName(), indexRepo);
			model.addAttchment(MetadataRepository.class, repo);
		}

		// build a VDB service
		VDBService vdb = new VDBService(deployment);
		final ServiceBuilder<VDBMetaData> vdbService = context.getServiceTarget().addService(TeiidServiceNames.vdbServiceName(deployment.getName(), deployment.getVersion()), vdb);
		
		// add dependencies to data-sources
		dataSourceDependencies(deployment, new DependentServices() {
			@Override
			public void dependentService(final String dsName, final ServiceName svcName) {
				DataSourceListener dsl = new DataSourceListener(dsName, svcName, vdbStatusChecker);									
				ServiceBuilder<DataSourceListener> sb = context.getServiceTarget().addService(TeiidServiceNames.dsListenerServiceName(deployment.getName(), deployment.getVersion(), dsName), dsl);
				sb.addDependency(svcName);
				sb.setInitialMode(Mode.PASSIVE).install();
			}
		});
		
		// adding the translator services is redundant, however if one is removed then it is an issue.
		for (Model model:deployment.getModels()) {
			List<String> sourceNames = model.getSourceNames();
			for (String sourceName:sourceNames) {
				String translatorName = model.getSourceTranslatorName(sourceName);
				if (!deployment.isOverideTranslator(translatorName)) {
					vdbService.addDependency(TeiidServiceNames.translatorServiceName(translatorName));
				}
			}
		}
		
		//override translators (if any)
		for (Translator t: deployment.getOverrideTranslators()) {
			VDBTranslatorMetaData data = (VDBTranslatorMetaData)t;
			String type = data.getType();
			vdbService.addDependency(TeiidServiceNames.translatorServiceName(type));
		}	
		
		vdbService.addDependency(TeiidServiceNames.VDB_REPO, VDBRepository.class,  vdb.getVDBRepositoryInjector());
		vdbService.addDependency(TeiidServiceNames.TRANSLATOR_REPO, TranslatorRepository.class,  vdb.getTranslatorRepositoryInjector());
		vdbService.addDependency(TeiidServiceNames.executorServiceName(this.asyncThreadPoolName), Executor.class,  vdb.getExecutorInjector());
		vdbService.addDependency(TeiidServiceNames.OBJECT_SERIALIZER, ObjectSerializer.class, vdb.getSerializerInjector());
		vdbService.addDependency(TeiidServiceNames.BUFFER_MGR, BufferServiceImpl.class, vdb.getBufferServiceInjector());
		vdbService.addDependency(DependencyType.OPTIONAL, TeiidServiceNames.OBJECT_REPLICATOR, ObjectReplicator.class, vdb.getObjectReplicatorInjector());
		vdbService.setInitialMode(Mode.PASSIVE).install();
		
		ServiceController<?> scMain = deploymentUnit.getServiceRegistry().getService(deploymentUnit.getServiceName().append("contents")); //$NON-NLS-1$
		scMain.addListener(new AbstractServiceListener<Object>() {
			@Override
		    public void serviceRemoveRequested(final ServiceController controller) {
				final VDBMetaData vdb = deploymentUnit.getAttachment(TeiidAttachments.VDB_METADATA);
				
				ServiceController<?> sc = deploymentUnit.getServiceRegistry().getService(TeiidServiceNames.OBJECT_SERIALIZER);
				if (sc != null) {
					ObjectSerializer serilalizer = ObjectSerializer.class.cast(sc.getValue());
					serilalizer.removeAttachments(vdb);	
					LogManager.logTrace(LogConstants.CTX_RUNTIME, "VDB "+vdb.getName()+" metadata removed"); //$NON-NLS-1$ //$NON-NLS-2$
				}
		    }			
		});
	}
	
	private void dataSourceDependencies(VDBMetaData deployment, DependentServices svcListener) {
		
		for (ModelMetaData model:deployment.getModelMetaDatas().values()) {
			for (String sourceName:model.getSourceNames()) {
				String translatorName = model.getSourceTranslatorName(sourceName);
				if (deployment.isOverideTranslator(translatorName)) {
					VDBTranslatorMetaData translator = deployment.getTranslator(translatorName);
					translatorName = translator.getType();
				}

				// Need to make the data source service as dependency; otherwise dynamic vdbs will not work correctly.
				String dsName = model.getSourceConnectionJndiName(sourceName);
				final ContextNames.BindInfo bindInfo = ContextNames.bindInfoFor(getJndiName(dsName));
				svcListener.dependentService(dsName, bindInfo.getBinderServiceName());				
			}
		}
	}
	
	interface DependentServices {
		void dependentService(String dsName, ServiceName svc);
	}
	
	static class DataSourceListener implements Service<DataSourceListener>{
		private VDBStatusChecker vdbStatusChecker;
		private String dsName;
		private ServiceName svcName;
		
		public DataSourceListener(String dsName, ServiceName svcName, VDBStatusChecker checker) {
			this.dsName = dsName;
			this.svcName = svcName;
			this.vdbStatusChecker = checker;
		}
		
		public DataSourceListener getValue() throws IllegalStateException,IllegalArgumentException {
			return this;
		}

		@Override
		public void start(StartContext context) throws StartException {
			ServiceController s = context.getController().getServiceContainer().getService(this.svcName);
			if (s != null) {
				this.vdbStatusChecker.dataSourceAdded(this.dsName);
			}
		}

		@Override
		public void stop(StopContext context) {
			ServiceController s = context.getController().getServiceContainer().getService(this.svcName);
			if (s.getMode().equals(Mode.REMOVE) || s.getState().equals(State.STOPPING)) {
				this.vdbStatusChecker.dataSourceRemoved(this.dsName);
			}
		}		
	}

	private String getJndiName(String name) {
		String jndiName = name;
		if (!name.startsWith(JAVA_CONTEXT)) {
			jndiName = JAVA_CONTEXT + jndiName;
		}
		return jndiName;
	}	
	
	@Override
	public void undeploy(final DeploymentUnit deploymentUnit) {
		if (!TeiidAttachments.isVDBDeployment(deploymentUnit)) {
			return;
		}	
	}
	
	private MetadataRepository getMetadataRepository(VDBMetaData vdb, String modelName, IndexMetadataRepository indexRepo) throws DeploymentUnitProcessingException {
		final ModelMetaData model = vdb.getModel(modelName);
				
		if (model.getSchemaSourceType() == null) {
			if (!vdb.isDynamic()) {
				return indexRepo;
			}
			
			if (vdb.isDynamic() && model.isSource()) {
				return new NativeMetadataRepository();
			}
			return null;
		}
		
		if (model.getSchemaSourceType().equalsIgnoreCase("DDL")) { //$NON-NLS-1$
			return new DDLMetadataRepository();
		}
		
		if (model.getSchemaSourceType().equalsIgnoreCase("INDEX")) { //$NON-NLS-1$
			return indexRepo;
		}
		
		if (model.getSchemaSourceType().equalsIgnoreCase("NATIVE")) { //$NON-NLS-1$
			return new NativeMetadataRepository();
		}		
		
		// if the schema type is a module based
        final Module module;
        ClassLoader moduleLoader = this.getClass().getClassLoader();
        ModuleLoader ml = Module.getCallerModuleLoader();
        if (model.getSchemaSourceType() != null && ml != null) {
	        try {
            	module = ml.loadModule(ModuleIdentifier.create(model.getSchemaSourceType()));
            	moduleLoader = module.getClassLoader();
	        } catch (ModuleLoadException e) {
	            throw new DeploymentUnitProcessingException(IntegrationPlugin.Util.getString("failed_load_module", IntegrationPlugin.Event.TEIID50068, model.getSchemaSourceType(), model.getName())); //$NON-NLS-1$
	        }
        }
        
        final ServiceLoader<MetadataRepository> serviceLoader =  ServiceLoader.load(MetadataRepository.class, moduleLoader);
        if (serviceLoader != null) {
        	for (MetadataRepository loader:serviceLoader) {
        		return loader;
        	}
        }
		return null;
	}	
}