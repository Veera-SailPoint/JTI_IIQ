package sailpoint.services.standard.connector;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import sailpoint.connector.ExpiredPasswordException;
import sailpoint.api.IdentityService;
import sailpoint.api.SailPointContext;
import sailpoint.api.SailPointFactory;
import sailpoint.connector.AbstractConnector;
import sailpoint.connector.AuthenticationFailedException;
import sailpoint.connector.Connector;
import sailpoint.connector.ConnectorException;
import sailpoint.connector.ConnectorFactory;
import sailpoint.connector.ObjectNotFoundException;
import sailpoint.object.Application;
import sailpoint.object.AttributeDefinition;
import sailpoint.object.Application.Feature;
import sailpoint.object.Attributes;
import sailpoint.object.Filter;
import sailpoint.object.Identity;
import sailpoint.object.Link;
import sailpoint.object.ManagedAttribute;
import sailpoint.object.Partition;
import sailpoint.object.ProvisioningPlan;
import sailpoint.object.ProvisioningPlan.AbstractRequest;
import sailpoint.object.ProvisioningPlan.AccountRequest;
import sailpoint.object.ProvisioningPlan.AttributeRequest;
import sailpoint.object.ProvisioningPlan.ObjectRequest;
import sailpoint.object.ProvisioningResult;
import sailpoint.object.QueryOptions;
import sailpoint.object.Resolver;
import sailpoint.object.ResourceObject;
import sailpoint.object.Rule;
import sailpoint.object.Schema;
import sailpoint.tools.CloseableIterator;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Message;

import org.apache.log4j.Logger;

import sailpoint.tools.Util;
import sailpoint.tools.xml.XMLReferenceResolver;

/**
 * This connector is meant to be a replacement for the Logical Connector. The
 * Logical connector's purpose is to allow some subsets accounts and their
 * access rights to be represented as a logical grouping, as an application.
 * <p>
 * The Logical connector supports both subsets of an application (single tier)
 * and combining multiple application accounts into a single, logical account
 * (multiple tiers). This connector mainly supports a single tier scenario, but
 * a customization rule or the LogiPlex split rule could be used to perform
 * side-lookups.
 *
 * @author menno.pieters@sailpoint.com
 */
public class LogiPlexConnector extends AbstractConnector {

	public final static String LOGIPLEX_ATTR_MASTER_APPLICATION = "masterApplication";
	public final static String LOGIPLEX_ATTR_MASTER_CONNECTOR = "logiPlexMasterConnector";
	public final static String LOGIPLEX_ATTR_LINK_FROM_MASTER = "linkFromMaster";
	public final static String LOGIPLEX_AGGREGATION_RULE = "logiPlexAggregationRule";
	public final static String LOGIPLEX_PROVISIONING_RULE = "logiPlexProvisioningRule";
	public final static String LOGIPLEX_PREFIX = "logiPlexPrefix";

	public final static String CONNECTOR_TYPE = "LogiPlex Connector";

	public final static String ARG_ORIGINAL_TARGET_APP = "logiPlexOrginalApplication";

	public static final String LOGIPLEX_TRACKING_ID = "logiplexTrackingId";

	private final Logger log = Logger.getLogger(LogiPlexConnector.class);

	private Application masterApplication = null;
	private Connector masterConnector = null;
	private SailPointContext context;
	private LogiPlexUtil util = null;
	private List<String> subApplicationNames = null;

	/**
	 * Standard constructor.
	 *
	 * @param application
	 *            Application definition containing the connector parameters,
	 *            schema, etc.
	 * @throws GeneralException
	 */
	public LogiPlexConnector(final Application application) throws GeneralException {
		super(application);
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Constructor: LogiPlexConnector(%s)", application)));
		}
		init(application);
	}

	/**
	 * Constructor when using instances.
	 *
	 * @param application
	 *            Application definition containing the connector parameters,
	 *            schema, etc.
	 * @param instance
	 *            Application instance
	 * @throws GeneralException
	 */
	public LogiPlexConnector(final Application application, final String instance) throws GeneralException {
		super(application, instance);
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Constructor: LogiPlexConnector(%s, %s)", application, instance)));
		}
		init(application, instance);
	}

	/**
	 * Called from the constructor to initialize the connector.
	 * 
	 * @param application
	 *            Application definition containing the connector parameters,
	 *            schema, etc.
	 * @throws GeneralException
	 */
	private void init(Application application) throws GeneralException {
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Enter: init(%s)", application)));
		}
		init(application, null);
	}

	/**
	 * Called from the constructor to initialize the connector. This method will
	 * initialize the connector configuration. To do so, it needs to figure out
	 * which application is the main and/or master application and which
	 * connector to use to connect to the target system. While doing that, it
	 * may also need to check any proxies, in case a cloud connector gateway is
	 * used.
	 *
	 * @param application
	 *            Application definition containing the connector parameters,
	 *            schema, etc.
	 * @param instance
	 * @throws GeneralException
	 */
	private void init(Application application, String instance) throws GeneralException {
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Enter: init(%s, %s)", application, instance)));
		}
		context = SailPointFactory.getCurrentContext();

		// Try to get the master application from the application definition.
		String masterApplicationId = application.getStringAttributeValue(LOGIPLEX_ATTR_MASTER_APPLICATION);
		if (application.getId() != null || masterApplicationId != null) {
			/*
			 * This part is only called in case that the connector is
			 * instantiated for 'real' work: preview, aggregation, provisioning.
			 */
			if (log.isTraceEnabled()) {
				log.trace("Instantiating LogiPlex Connector");
			}

			Application proxyApplication = null;
			if (Util.isNullOrEmpty(masterApplicationId)) {
				proxyApplication = application.getProxy();
				if (proxyApplication != null) {
					log.debug("No master application found. Found proxy: checking proxy for master application.");
					masterApplicationId = proxyApplication.getStringAttributeValue(LOGIPLEX_ATTR_MASTER_APPLICATION);
				}
			}
			if (Util.isNullOrEmpty(masterApplicationId) || Util.nullSafeEq(masterApplicationId, application.getId())) {
				/*
				 * Adapter mode: master = main. In adapter mode there is no
				 * longer separation between the main and master configuration.
				 */
				String masterConnectorName = application.getStringAttributeValue(LOGIPLEX_ATTR_MASTER_CONNECTOR);
				if (Util.isNullOrEmpty(masterConnectorName) && proxyApplication != null) {
					if (log.isDebugEnabled()) {
						log.debug(String.format("Application %s does not specify a master connector, checking proxy %s", application.getName(), proxyApplication.getName()));
					}
					masterConnectorName = proxyApplication.getStringAttributeValue(LOGIPLEX_ATTR_MASTER_CONNECTOR);
				}
				if (Util.isNotNullOrEmpty(masterConnectorName)) {
					if (log.isTraceEnabled()) {
						log.trace(String.format("Adapter mode: Using LogiPlex Master Connector %s", masterConnectorName));
					}
					boolean proxyIsLogiPlex = ((proxyApplication != null) && (LogiPlexConnector.class.getName().equals(proxyApplication.getConnector())));
					masterApplication = ((proxyIsLogiPlex) ? proxyApplication : application);
					masterConnector = ConnectorFactory.createConnector(masterConnectorName, (proxyIsLogiPlex ? proxyApplication : application), instance);
				} else {
					/*
					 * This indicates a configuration issue.
					 */
					log.warn("No master application or connector defined");
				}
			} else {
				/*
				 * Classic mode: master != main. In classic mode there is a
				 * separate application definition for the master and the main
				 * application. The main application must specify the master
				 * application name or id.
				 */
				masterApplication = context.getObject(Application.class, masterApplicationId);
				if (masterApplication == null) {
					throw new GeneralException("Cannot resolve master application " + masterApplicationId);
				}
				super.setApplication(masterApplication);
				masterConnector = ConnectorFactory.getConnector(masterApplication, instance);
			}
			if (masterConnector == null) {
				throw new GeneralException(String.format("Cannot instantiate master connector for application %s", (application == null) ? "NULL" : application.getName()));
			}
		} else {
			/*
			 * Dummy instance: this is called for example when opening the
			 * application definition. There is no need to instantiate the
			 * master connector.
			 */
			log.info("Dummy instance, no id - ignoring");
		}
		/*
		 * Instantiate the LogiPlexUtil class, which provided utility methods
		 * used during aggregation and provisioning.
		 */
		this.util = new LogiPlexUtil(context, application.getName(), application.getAttributes());
	}

	@Override
	public String getConnectorType() {
		if (log.isDebugEnabled()) {
			log.debug("Enter: getConnectorType()");
		}
		return CONNECTOR_TYPE;
	}

	@SuppressWarnings("deprecation")
	@Override
	public List<Feature> getSupportedFeatures() {
		if (log.isDebugEnabled()) {
			log.debug("Enter: getSupportedFeatures()");
		}
		/*
		 * Deprecated function, but kept for compatibility reasons.
		 */
		return this.masterConnector.getSupportedFeatures();
	}

	/**
	 * Get a single account or group object.
	 * 
	 * @param objectType
	 * @param identityName
	 * @param options
	 * @return
	 * @throws ConnectorException
	 */
	public ResourceObject getObject(final String objectType, final String identityName, final Map<String, Object> options) throws ConnectorException {
		if (masterConnector == null) {
			throw new ConnectorException("No master connector set");
		}
		ResourceObject object = this.masterConnector.getObject(objectType, identityName, options);
		Map<String, ResourceObject> objects;
		try {
			objects = util.splitResourceObject(object);
			String targetApplicationName = null;
			Application targetApplication = getTargetApplication();
			if (targetApplication == null) {
				targetApplicationName = this.getApplication().getName();
			} else {
				targetApplicationName = targetApplication.getName();
			}
			return objects.get(targetApplicationName);
		} catch (GeneralException e) {
			log.error(e);
			throw new ConnectorException(e);
		}
	}

	/**
	 * Get an iterator for the accounts or group objects based on the connector
	 * configuration and provided options.
	 * 
	 * @param objectType
	 * @param filter
	 * @param options
	 * @return
	 * @throws ConnectorException
	 */
	public CloseableIterator<ResourceObject> iterateObjects(final String objectType, final Filter filter, final Map<String, Object> options) throws ConnectorException {
		if (masterConnector == null) {
			throw new ConnectorException("No master connector set");
		}
		if (log.isDebugEnabled()) {
			/*
			 * Debug code: check available schemas.
			 */
			log.debug(LogiPlexTools.stripControls(String.format("Enter: iterateObjects(%s, %s, %s)", objectType, filter, options)));
			Application masterApp = this.masterConnector.getApplication();
			log.debug(LogiPlexTools.stripControls(String.format("Application for master connector: %s", masterApp)));
			if (masterApp != null) {
				List<Schema> schemas = masterApp.getSchemas();
				if (schemas == null) {
					log.warn("No schemas");
				} else {
					for (Schema schema : schemas) {
						log.debug(LogiPlexTools.stripControls(String.format("Schema defined for %s", schema.getObjectType())));
					}
				}
			}
		}
		/*
		 * Call the corresponding method on the master connector. The iterator
		 * returned by the master connector will be used to read and process
		 * objects using the LogiPlexIterator.
		 */
		CloseableIterator<ResourceObject> masterIterator = this.masterConnector.iterateObjects(objectType, filter, options);
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Got master iterator: %s)", masterIterator)));
		}
		CloseableIterator<ResourceObject> iterator = null;
		try {
			if (log.isDebugEnabled()) {
				log.debug("Getting LogiPlexIterator using master iterator");
			}
			iterator = new LogiPlexIterator<ResourceObject>(objectType, masterConnector, masterIterator, this.util, options);
		} catch (GeneralException e) {
			log.error(e);
			throw new ConnectorException(e);
		}
		if (log.isTraceEnabled()) {
			log.trace(LogiPlexTools.stripControls(String.format("Return iterator: %s", iterator)));
		}

		return iterator;
	}

	/**
	 * Get an iterator for partitioned aggregation.
	 * 
	 * @param partition
	 * @return
	 * @throws ConnectorException
	 */
	@Override
	public CloseableIterator<ResourceObject> iterateObjects(Partition partition) throws ConnectorException {
		if (masterConnector == null) {
			throw new ConnectorException("No master connector set");
		}
		if (log.isDebugEnabled()) {
			/*
			 * Debug code: check available schemas.
			 */
			log.debug(LogiPlexTools.stripControls(String.format("Enter: iterateObjects(%s)", partition)));
			Application masterApp = this.masterConnector.getApplication();
			log.debug(LogiPlexTools.stripControls(String.format("Application for master connector: %s", masterApp)));
			if (masterApp != null) {
				List<Schema> schemas = masterApp.getSchemas();
				if (schemas == null) {
					log.warn("No schemas");
				} else {
					for (Schema schema : schemas) {
						log.debug(LogiPlexTools.stripControls(String.format("Schema defined for %s", schema.getObjectType())));
					}
				}
			}
		}
		/*
		 * Call the corresponding method on the master connector. The iterator
		 * returned by the master connector will be used to read and process
		 * objects using the LogiPlexIterator.
		 */
		CloseableIterator<ResourceObject> masterIterator = this.masterConnector.iterateObjects(partition);
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Got master iterator for partition: %s)", masterIterator)));
		}
		CloseableIterator<ResourceObject> iterator = null;
		try {
			if (log.isDebugEnabled()) {
				log.debug("Getting LogiPlexIterator for partition using master iterator");
			}
			iterator = new LogiPlexIterator<ResourceObject>(partition.getObjectType(), masterConnector, masterIterator, this.util, new HashMap<String, Object>());
		} catch (GeneralException e) {
			log.error(e);
			throw new ConnectorException(e);
		}
		if (log.isTraceEnabled()) {
			log.trace(LogiPlexTools.stripControls(String.format("Return partition iterator: %s", iterator)));
		}

		return iterator;
	}

	/**
	 * This methor will ask the master connector to generate a list of
	 * partitions.
	 * 
	 * @param objectType
	 * @param suggestedPartitionCount
	 * @param filter
	 * @param ops
	 * @return
	 * @throws ConnectorException
	 */
	@Override
	public List<Partition> getIteratorPartitions(String objectType, int suggestedPartitionCount, Filter filter, Map<String, Object> ops) throws ConnectorException {
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Enter: getIteratorPartitions(%s, %d, %s, %s)", objectType, suggestedPartitionCount, filter, ops)));
		}
		if (masterConnector == null) {
			throw new ConnectorException("No master connector set");
		}
		return masterConnector.getIteratorPartitions(objectType, suggestedPartitionCount, filter, ops);
	}

	/**
	 * Create a plan to remove all entitlements for a sub-application on the
	 * master if a request to delete a sub-application is encountered.
	 * 
	 * @param link
	 * @param arguments
	 * @return
	 */
	public AccountRequest subAccountRemovalPlan(Link link, Attributes<String, Object> arguments) {
		AccountRequest accountRequest = null;
		if (link != null) {
			Attributes<String, Object> attributes = link.getAttributes();
			if (attributes != null) {
				List<String> entitlementAttributes = LogiPlexTools.getAccountEntitlementAttributes(link.getApplication());
				Map<String, Object> entitlementValues = new HashMap<String, Object>();
				if (!entitlementAttributes.isEmpty()) {
					for (String entitlementAttribute : entitlementAttributes) {
						@SuppressWarnings("unchecked")
						List<String> values = (List<String>) attributes.get(entitlementAttribute);
						if (values != null) {
							entitlementValues.put(entitlementAttribute, values);
						}
					}
				}
				if (entitlementValues != null && !entitlementValues.isEmpty()) {
					accountRequest = new AccountRequest();
					accountRequest.setNativeIdentity(link.getNativeIdentity());
					accountRequest.setInstance(link.getInstance());
					accountRequest.setOperation(AccountRequest.Operation.Modify);
					accountRequest.setApplication(LogiPlexTools.getMainApplication(link.getApplication()).getName());
					accountRequest.setArguments(arguments);
					for (String name : entitlementValues.keySet()) {
						Object values = entitlementValues.get(name);
						if (values instanceof List) {
							@SuppressWarnings("unchecked")
							List<String> listValues = (List<String>) values;
							for (String value : listValues) {
								accountRequest.add(new AttributeRequest(name, ProvisioningPlan.Operation.Remove, value));
							}
						} else {
							accountRequest.add(new AttributeRequest(name, ProvisioningPlan.Operation.Remove, values));
						}
					}
					accountRequest.addArgument(ARG_ORIGINAL_TARGET_APP, link.getApplicationName());
				}
			}
		}
		return accountRequest;
	}

	/**
	 * Apply default logic to transform the plans for sub-applications into
	 * information for the main application. The default logic will ignore
	 * attribute changes, other than entitlements for sub-application.
	 * 
	 * When operations are processed for the master application, they may need
	 * to be applied to the sub-applications, too.
	 *
	 * @param plan
	 *            The ProvisioningPlan object to be inspected and modified.
	 * @param originalPlan
	 * @param identity
	 *            The identity for which the plan is to be executed.
	 * @return A modified ProvisioningPlan object.
	 * @throws GeneralException
	 */
	public ProvisioningPlan runDefaultProvisioningMergeLogic(ProvisioningPlan plan, ProvisioningPlan originalPlan, Identity identity) throws GeneralException {
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Enter: runDefaultProvisioningMergeLogic(%s, %s)", plan, identity)));
		}
		if (plan != null && identity != null) {
			ProvisioningPlan newPlan = new ProvisioningPlan();
			newPlan.setNativeIdentity(plan.getNativeIdentity());
			newPlan.setArguments(plan.getArguments());

			List<AccountRequest> accountRequests = plan.getAccountRequests();
			if (accountRequests != null && !accountRequests.isEmpty()) {
				for (AccountRequest accountRequest : accountRequests) {
					if (log.isTraceEnabled() && accountRequest != null) {
						log.trace(accountRequest.toXml());
						log.trace(String.format("Operation: %s", accountRequest.getOperation().toString()));
					}
					boolean isMainApplicationRequest = LogiPlexTools.isMainApplicationRequest(this.getApplication(), accountRequest);
					switch (accountRequest.getOperation()) {
					case Create:
						Link mainLink = LogiPlexTools.getMainLink(context, identity, this.getApplication(), accountRequest);
						if (isMainApplicationRequest || mainLink == null) {
							if (log.isTraceEnabled()) {
								log.trace(String.format("MainLink: %s", ((mainLink == null) ? "NULL" : mainLink.toXml())));
							}
							if (!isMainApplicationRequest) {
								// ...meaning mainLink is null
								AccountRequest newAccountRequest = (AccountRequest) accountRequest.deepCopy(this.context);
								Application mainApplication = LogiPlexTools.getMainApplication(getApplication());
								newAccountRequest.setApplication(mainApplication.getName());
								newPlan.add(newAccountRequest);
							} else {
								newPlan.add(accountRequest);
							}
						} else {
							// Just add the entitlements
							List<AttributeRequest> atrqs = new ArrayList<AttributeRequest>();

							List<String> entitlementAttributes = LogiPlexTools.getAccountEntitlementAttributes(this.getApplication());
							for (String entitlementAttribute : entitlementAttributes) {
								List<AttributeRequest> eatrqs = accountRequest.getAttributeRequests(entitlementAttribute);
								if (eatrqs != null && !eatrqs.isEmpty()) {
									atrqs.addAll(eatrqs);
								}
							}

							// Create a new list and copy modified versions of
							// each attribute request into the list.
							List<AttributeRequest> newAtrqs = new ArrayList<AttributeRequest>();
							if (atrqs != null && !atrqs.isEmpty()) {
								for (AttributeRequest atrq : atrqs) {
									if (ProvisioningPlan.Operation.Set.equals(atrq.getOperation()) || ProvisioningPlan.Operation.Add.equals(atrq.getOperation())) {
										atrq.setOperation(ProvisioningPlan.Operation.Add);
										newAtrqs.add(atrq);
									}
								}
							}
							if (!newAtrqs.isEmpty()) {
								AccountRequest nar = new AccountRequest();
								nar.setNativeIdentity(mainLink.getNativeIdentity());
								nar.setInstance(accountRequest.getInstance());
								nar.setOperation(AccountRequest.Operation.Modify);
								nar.setApplication(LogiPlexTools.getMainApplication(this.getApplication()).getName());
								nar.setArguments(accountRequest.getArguments());
								nar.setAttributeRequests(newAtrqs);
								newPlan.add(nar);
							}

							// In case of "Create" operation we need to remove
							// attributes that are not passed to master
							// application connector
							// Current account state may be different from what
							// has been requested in "Create" request, e.g.
							// ProvisioningPolicy says that
							// new account should be disabled, but existing AD
							// account is enabled. In such case AccessRequest
							// will never be verified

							if (originalPlan != null) {
								List<String> mainAppEntitlementAttributes = LogiPlexTools.getAccountEntitlementAttributes(masterApplication);
								AccountRequest matchingAccountRequest = LogiPlexTools.getMatchingAccountRequest(originalPlan, accountRequest);
								if (matchingAccountRequest != null && !Util.isEmpty(mainAppEntitlementAttributes)) {
									List<AttributeRequest> attributeRequests = matchingAccountRequest.getAttributeRequests();
									if (!Util.isEmpty(attributeRequests)) {
										Iterator<AttributeRequest> attributeRequestIterator = attributeRequests.iterator();
										while (attributeRequestIterator.hasNext()) {
											AttributeRequest attributeRequest = attributeRequestIterator.next();
											if (attributeRequest != null) {
												if (!mainAppEntitlementAttributes.contains(attributeRequest.getName())) {
													attributeRequestIterator.remove();
												}
											}
										}
									}
								}
							}
						}
						break;
					case Enable:
					case Disable:
					case Lock:
					case Unlock:
						// Only handle Enable/Disable/Lock/Unlock for the main
						// application,
						// not for any sub-application.
						if (isMainApplicationRequest) {
							newPlan.add(accountRequest);
						}
						break;
					case Modify:
						if (isMainApplicationRequest) {
							newPlan.add(accountRequest);
						} else {
							// For modification of a sub-application, we have to
							// carefully figure out what to do.
							// We need to compare the requested change to the
							// current state.
							Link link = LogiPlexTools.getLink(context, identity, accountRequest);
							if (link != null) {
								List<AttributeRequest> atrqs = new ArrayList<AttributeRequest>();
								List<String> entitlementAttributes = LogiPlexTools.getAccountEntitlementAttributes(this.getApplication());
								for (String entitlementAttribute : entitlementAttributes) {
									List<AttributeRequest> eatrqs = accountRequest.getAttributeRequests(entitlementAttribute);
									if (eatrqs != null && !eatrqs.isEmpty()) {
										atrqs.addAll(eatrqs);
									}
								}
								List<AttributeRequest> newAtrqs = new ArrayList<AttributeRequest>();
								if (atrqs != null && !atrqs.isEmpty()) {
									for (AttributeRequest atrq : atrqs) {
										// Only handle entitlement attributes.
										String attributeName = atrq.getName();
										if (LogiPlexTools.isAccountEntitlementAttribute(this.getApplication(), attributeName)) {
											if (ProvisioningPlan.Operation.Set.equals(atrq.getOperation())) {
												if (LogiPlexTools.isMultiValuedAccountAttribute(this.getApplication(), attributeName)) {
													Attributes<String, Object> attributes = link.getAttributes();
													if (attributes != null && !attributes.isEmpty()) {
														@SuppressWarnings("unchecked")
														List<String> oldValues = (List<String>) attributes.getList(attributeName);
														List<String> newValues = new ArrayList<String>();
														Object value = atrq.getValue();
														if (value instanceof List) {
															@SuppressWarnings("unchecked")
															List<String> listValue = (List<String>) value;
															newValues.addAll(listValue);
														} else if (value != null) {
															newValues.add(value.toString());
														}

														@SuppressWarnings("unchecked")
														List<String> toAdd = (List<String>) LogiPlexTools.getItemsToAdd(oldValues, newValues);
														@SuppressWarnings("unchecked")
														List<String> toRemove = (List<String>) LogiPlexTools.getItemsToRemove(oldValues, newValues);
														for (String val : toRemove) {
															newAtrqs.add(new AttributeRequest(attributeName, ProvisioningPlan.Operation.Remove, val));
														}
														for (String val : toAdd) {
															newAtrqs.add(new AttributeRequest(attributeName, ProvisioningPlan.Operation.Add, val));
														}
													}
												} else {
													newAtrqs.add(atrq);
												}
											} else {
												newAtrqs.add(atrq);
											}
										}
									}
									if (!newAtrqs.isEmpty()) {
										AccountRequest nar = new AccountRequest();
										nar.setNativeIdentity(accountRequest.getNativeIdentity());
										nar.setInstance(accountRequest.getInstance());
										nar.setOperation(AccountRequest.Operation.Modify);
										nar.setApplication(LogiPlexTools.getMainApplication(this.getApplication()).getName());
										nar.setArguments(accountRequest.getArguments());
										nar.setAttributeRequests(newAtrqs);
										newPlan.add(nar);
									}
								}
							}
						}
						break;
					case Delete:
						if (isMainApplicationRequest) {
							// Look up corresponding sub-accounts to be deleted
							// as well.
							List<String> subApplicationNames = LogiPlexTools.getSubApplicationNames(context, accountRequest.getApplication(context));
							if (subApplicationNames != null && !subApplicationNames.isEmpty()) {
								IdentityService service = new IdentityService(context);
								for (String subApplicationName : subApplicationNames) {
									Application subApplication = context.getObjectByName(Application.class, subApplicationName);
									if (subApplication != null) {
										Link subApplicationLink = service.getLink(identity, subApplication, accountRequest.getInstance(), accountRequest.getNativeIdentity());
										if (subApplicationLink != null) {
											AccountRequest subAccountRequest = (AccountRequest) accountRequest.deepCopy((XMLReferenceResolver) context);
											subAccountRequest.setApplication(subApplicationName);
											newPlan.add(subAccountRequest);
										}
									}
								}
							}
							// Add the main account as the last to do.
							newPlan.add(accountRequest);
						} else {
							// For deletion of a sub-application, we remove all
							// values for entitlements.
							Link link = LogiPlexTools.getLink(context, identity, accountRequest);
							AccountRequest nar = subAccountRemovalPlan(link, accountRequest.getArguments());
							if (nar != null) {
								newPlan.add(nar);
							}
						}
						break;
					default:
						break;
					}
				}
			}

			if (log.isTraceEnabled()) {
				log.trace(newPlan.toXml());
			}
			return newPlan;
		}
		return plan;

	}

	/**
	 * Find and run the LogiPlex Provisioning Rule, if set. Otherwise, use the
	 * default logic.
	 *
	 * @param plan
	 *            The ProvisioningPlan object to be inspected and modified.
	 * @return A modified ProvisioningPlan object.
	 * @throws ConnectorException
	 * @throws GeneralException
	 */
	private ProvisioningPlan runProvisioningMergeRule(ProvisioningPlan plan, ProvisioningPlan originalPlan) throws ConnectorException, GeneralException {
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Enter: runProvisioningMergeRule(%s)", plan)));
		}
		String ruleName = this.getApplication().getStringAttributeValue(LOGIPLEX_PROVISIONING_RULE);
		Rule rule = null;

		// If configured, try to retrieve the rule.
		if (Util.isNotNullOrEmpty(ruleName)) {
			log.debug("Looking up provisioning rule: " + ruleName);
			rule = context.getObjectByName(Rule.class, ruleName);
			if (rule == null) {
				throw new ConnectorException(LogiPlexTools.stripControls(String.format("Rule %s not found", ruleName)));
			}
		} else {
			if (log.isInfoEnabled()) {
				log.info("No rule configured, use default logic");
			}
		}

		// Determine identity from plan
		Identity identity = plan.getIdentity();
		if (identity == null) {
			log.debug("Could not get identity directly from the plan, try native identity name from the plan");
			identity = context.getObjectByName(Identity.class, plan.getNativeIdentity());
			if (identity == null) {
				log.warn("Could not get identity from the plan. Identity will be set to null in rule arguments");
			}
		}

		if (rule != null) {
			Map<String, Object> args = new HashMap<String, Object>();
			args.put("context", context);
			args.put("log", log);
			args.put("plan", plan);
			args.put("originalPlan", originalPlan);
			args.put("identity", identity);
			args.put("application", this.getApplication());
			args.put("masterApplication", this.masterApplication);
			args.put("connector", this);
			args.put("masterConnector", this.masterConnector);
			log.debug(LogiPlexTools.stripControls(String.format("Running provisioning rule %s with arguments %s", ruleName, args.toString())));
			Object result = context.runRule(rule, args);
			if (result instanceof ProvisioningPlan) {
				if (log.isTraceEnabled()) {
					log.trace(LogiPlexTools.stripControls(String.format("Modified plan: %s", ((ProvisioningPlan) result).toXml())));
				}
				plan = (ProvisioningPlan) result;
			} else if (result != null) {
				throw new GeneralException(LogiPlexTools.stripControls(String.format("Incorrect result type %s from provisioning rule", result.getClass().getName())));
			}
		} else {
			plan = runDefaultProvisioningMergeLogic(plan, originalPlan, identity);
		}
		return plan;
	}

	private List<String> getSubApplicationNames(Application application) throws GeneralException {
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Enter: getSubApplicationNames(%s)", application)));
		}

		if (subApplicationNames == null) {
			subApplicationNames = LogiPlexTools.getSubApplicationNames(context, application);
		}
		return subApplicationNames;
	}

	@Override
	public ProvisioningResult provision(ProvisioningPlan originalPlan) throws ConnectorException {
		if (originalPlan != null) {
			if (log.isDebugEnabled()) {
				log.debug(LogiPlexTools.stripControls(String.format("Enter: provision(%s)", originalPlan)));
			}
			if (log.isTraceEnabled() && originalPlan != null) {
				try {
					log.trace(LogiPlexTools.stripControls(String.format("Provisioning plan: %s", originalPlan.toXml())));
				} catch (GeneralException e) {
					log.error(e);
				}
			}
			if (masterApplication == null) {
				throw new ConnectorException("No master application set");
			}
			if (masterConnector == null) {
				throw new ConnectorException("No master connector set");
			}
			try {
				LogiPlexTools.setTrackingIDOnOriginalPlan(originalPlan);
				// Clone original plan
				ProvisioningPlan plan = (ProvisioningPlan) originalPlan.deepCopy(context);
				List<AccountRequest> accountRequests = plan.getAccountRequests();
				List<ObjectRequest> objectRequests = plan.getObjectRequests();

				// Store the original target application for later use
				if (accountRequests != null && !accountRequests.isEmpty()) {
					for (AccountRequest accountRequest : accountRequests) {
						accountRequest.put(ARG_ORIGINAL_TARGET_APP, accountRequest.getApplication());
					}
				}
				if (objectRequests != null && !objectRequests.isEmpty()) {
					for (ObjectRequest objectRequest : objectRequests) {
						objectRequest.put(ARG_ORIGINAL_TARGET_APP, objectRequest.getApplication());
					}
				}

				// Process Plan
				plan = runProvisioningMergeRule(plan, originalPlan);

				// Provision through master connector.
				if (plan != null && plan.getAccountRequests() != null && (!plan.getAccountRequests().isEmpty() || !plan.getObjectRequests().isEmpty())) {
					ProvisioningResult masterResult = masterConnector.provision(plan);
					if (masterResult == null) {
						masterResult = new ProvisioningResult();
					}

					// Re-get
					accountRequests = plan.getAccountRequests();
					objectRequests = plan.getObjectRequests();
					if (!(masterApplication.supportsFeature(Feature.NO_AGGREGATION) || masterApplication.supportsFeature(Feature.NO_AGGREGATION))) {
						// We should be able to read back the account
						// information
						if (accountRequests != null && !accountRequests.isEmpty()) {
							// Accounts
							for (AccountRequest accountRequest : plan.getAccountRequests()) {
								String nativeIdentity = accountRequest.getNativeIdentity();
								ProvisioningResult subResult = accountRequest.getResult();
								if (subResult == null) {
									subResult = new ProvisioningResult();
									if (masterResult.isCommitted()) {
										subResult.setStatus(ProvisioningResult.STATUS_COMMITTED);
									}
								}
								if (!AccountRequest.Operation.Delete.equals(accountRequest.getOperation())) {
									try {
										ResourceObject object = masterConnector.getObject("account", nativeIdentity, new HashMap<String, Object>());
										Map<String, ResourceObject> splitObjects = util.splitResourceObject(object);
										String orginalTarget = (String) accountRequest.getArgument(ARG_ORIGINAL_TARGET_APP);
										ResourceObject resultObject = null;
										if (orginalTarget != null) {
											log.debug(LogiPlexTools.stripControls(String.format("Original target application: %s", orginalTarget)));
											resultObject = splitObjects.get(orginalTarget);
											accountRequest.setApplication(orginalTarget);
										}
										if (resultObject != null) {
											if (log.isTraceEnabled()) {
												log.trace(LogiPlexTools.stripControls(
														String.format("Retrieved ResourceObject for account %s on %s: %s", nativeIdentity, accountRequest.getApplication(), resultObject.toXml())));
											}
											subResult.setObject(resultObject);
											if (masterResult.getObject() == null) {
												masterResult.setObject(resultObject);
											}
										}
									} catch (Exception e) {
										e.printStackTrace();
										log.error(LogiPlexTools.stripControls(String.format("Error while reading back account: %s", e.getMessage())));
										subResult.addError(e);
									}
								} else {
									// TODO: create a ResourceObject for
									// deletion
								}

								LogiPlexTools.updateResultsInOriginalPlan(originalPlan, accountRequest);
							}
						} else if (objectRequests != null && !objectRequests.isEmpty()) {
							// Groups
							ObjectRequest objectRequest = plan.getObjectRequests().get(0);
							String nativeIdentity = objectRequest.getNativeIdentity();
							String objectType = objectRequest.getType();
							// TODO: Aggregate back
						}
					}

					// Gather errors and warnings from account and object
					// requests
					// and add these to the original plan and provisioning
					// result
					// returned from the method.
					if (accountRequests != null && !accountRequests.isEmpty()) {
						for (AccountRequest accountRequest : plan.getAccountRequests()) {
							ProvisioningResult subResult = accountRequest.getResult();
							if (subResult != null) {
								List<Message> errors = subResult.getErrors();
								List<Message> warnings = subResult.getWarnings();
								if (errors != null) {
									for (Message message : errors) {
										masterResult.addError(message);
									}
								}
								if (warnings != null) {
									for (Message message : warnings) {
										masterResult.addWarning(message);
									}
								}
							}
						}
					}
					if (objectRequests != null && !objectRequests.isEmpty()) {
						for (ObjectRequest objectRequest : plan.getObjectRequests()) {
							ProvisioningResult subResult = objectRequest.getResult();
							if (subResult != null) {
								List<Message> errors = subResult.getErrors();
								List<Message> warnings = subResult.getWarnings();
								if (errors != null) {
									for (Message message : errors) {
										masterResult.addError(message);
									}
								}
								if (warnings != null) {
									for (Message message : warnings) {
										masterResult.addWarning(message);
									}
								}
							}
						}
					}

					// Add the generated account requests to the original plan
					if (accountRequests != null && !accountRequests.isEmpty()) {
						for (AccountRequest accountRequest : accountRequests) {
							String originalTarget = (String) accountRequest.getArgument(ARG_ORIGINAL_TARGET_APP);
							// todo there is possibly something incorect with
							// line below, we could end up with two account
							// requests
							// todo this happens when there is request to create
							// initial AD account coming from one of the
							// "logical" applications
							// todo
							if (!LogiPlexTools.getMainApplication(getApplication()).getName().equals(originalTarget)) {
								originalPlan.add(accountRequest);
							} else {
								Identity identity = context.getObjectByName(Identity.class, originalPlan.getNativeIdentity());
								if (identity == null) {
									throw new GeneralException("Plan does not have an identity");
								}
								switch (accountRequest.getOperation()) {
								case Delete:
								case Enable:
								case Disable:
								case Lock:
								case Unlock:
									// For these operations, apply the changes
									// to the sub-applications as well.
									List<String> san = getSubApplicationNames(getApplication());
									if (san != null) {
										for (String name : san) {
											Application subApp = context.getObjectByName(Application.class, name);
											IdentityService service = new IdentityService(context);
											Link link = service.getLink(identity, subApp, accountRequest.getInstance(), accountRequest.getNativeIdentity());
											if (link != null) {
												AccountRequest subAccountRequest = new AccountRequest(accountRequest);
												subAccountRequest.setApplication(name);
												originalPlan.add(subAccountRequest);
											}
										}
									}
									break;
								default:
									break;
								}
							}
						}
					}
					// Add the generated object requests to the original plan
					if (objectRequests != null && !objectRequests.isEmpty()) {
						for (ObjectRequest objectRequest : objectRequests) {
							String originalTarget = (String) objectRequest.getArgument(ARG_ORIGINAL_TARGET_APP);
							if (!LogiPlexTools.getMainApplication(getApplication()).getName().equals(originalTarget)) {
								originalPlan.add(objectRequest);
							}
						}
					}

					// Set the result(s) on the original plan and return the
					// composed result.
					originalPlan.setResult(masterResult);
					if (log.isTraceEnabled()) {
						log.trace(originalPlan.toXml());
					}
					return masterResult;
				} else {
					log.warn("No plan or empty plan - ignoring.");
					return new ProvisioningResult();
				}
			} catch (GeneralException e) {
				log.error(e);
				throw new ConnectorException(e);
			}
		}

		ProvisioningResult planResult = null;
		return planResult;
	}

	/**
	 * Pass authentication request on to the master connector.
	 * 
	 * @param accountId
	 * @param options
	 * @return
	 * @throws ConnectorException
	 * @throws ObjectNotFoundException
	 * @throws AuthenticationFailedException
	 * @throws ExpiredPasswordException
	 */
	@Override
	public ResourceObject authenticate(String accountId, Map<String, Object> options) throws ConnectorException, ObjectNotFoundException, AuthenticationFailedException, ExpiredPasswordException {
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Enter: authenticate(%s, %s)", accountId, options)));
		}
		return this.masterConnector.authenticate(accountId, options);
	}

	/**
	 * Pass authentication request on to the master connector.
	 * 
	 * @param username
	 * @param password
	 * @return
	 * @throws ConnectorException
	 * @throws ObjectNotFoundException
	 * @throws AuthenticationFailedException
	 * @throws ExpiredPasswordException
	 */
	@Override
	public ResourceObject authenticate(String username, String password) throws ConnectorException, ObjectNotFoundException, AuthenticationFailedException, ExpiredPasswordException {
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Enter: authenticate(%s, %s)", username, "********")));
		}
		return this.masterConnector.authenticate(username, password);
	}

	/**
	 * Pass requests to verify provisioning results on to the master connector.
	 * 
	 * @param id
	 * @return
	 * @throws ConnectorException
	 * @throws GeneralException
	 */
	@Override
	public ProvisioningResult checkStatus(String id) throws ConnectorException, GeneralException {
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Enter: checkStatus(%s)", id)));
		}
		return this.masterConnector.checkStatus(id);
	}

	@Override
	public List<AttributeDefinition> getDefaultAttributes() {
		if (log.isDebugEnabled()) {
			log.debug("Enter: getDefaultAttributes()");
		}
		List<AttributeDefinition> defaultAttributes = new ArrayList<AttributeDefinition>();
		defaultAttributes.add(new AttributeDefinition("masterApplication", "string", "Master application name or id", true, ""));
		defaultAttributes.add(new AttributeDefinition("logiPlexPrefix", "string", "Prefix for generated applications", false, ""));
		return defaultAttributes;
	}

	/**
	 * Test the connector. This request is passed on to the master connector.
	 * 
	 * @throws ConnectorException
	 */
	public void testConfiguration() throws ConnectorException {
		if (log.isDebugEnabled()) {
			log.debug("Enter: testConfiguration()");
		}
		if (masterConnector == null) {
			throw new ConnectorException("No master connector set");
		}
		this.masterConnector.testConfiguration();
	}

	@SuppressWarnings("deprecation")
	@Override
	public List<Schema> getDefaultSchemas() {
		if (log.isDebugEnabled()) {
			log.debug("Enter: getDefaultSchemas()");
		}
		return this.masterConnector.getDefaultSchemas();
	}

	/**
	 * Use the master connector to discover the schema. If that doesn't work, it
	 * will try to fetch the defined schema from the master application
	 * definition.
	 * 
	 * @param objectType
	 * @param options
	 * @return
	 * @throws ConnectorException
	 */
	@Override
	public Schema discoverSchema(final String objectType, final Map<String, Object> options) throws ConnectorException {
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Enter: discoverSchema(%s, %s)", objectType, options)));
		}
		if (masterApplication == null) {
			throw new ConnectorException("No master application set");
		}
		if (masterConnector == null) {
			throw new ConnectorException("No master connector set");
		}
		try {
			Schema schema = this.masterConnector.discoverSchema(objectType, options);
			schema = (Schema) schema.deepCopy((Resolver) context);
			schema.setId(null);
			return schema;
		} catch (java.lang.UnsupportedOperationException e) {
			log.warn(LogiPlexTools.stripControls(String.format("Master Connector threw exception while trying to discover schema: %s", e.getMessage())));
			if (log.isDebugEnabled()) {
				log.debug(LogiPlexTools.stripControls(String.format("Getting Schema for object type %s from master application", objectType)));
			}
			Schema schema = masterApplication.getSchema(objectType);
			if (schema != null) {
				try {
					schema = (Schema) schema.deepCopy((Resolver) context);
					schema.setId(null);
					return schema;
				} catch (GeneralException e1) {
					log.error(e);
					throw new ConnectorException(e);
				}
			}
			throw new ConnectorException(LogiPlexTools.stripControls(String.format("Master application does not have a schema for object type %s", objectType)));
		} catch (GeneralException e) {
			log.error(e);
			throw new ConnectorException(e);
		}
	}

	@Override
	public Map<String, Object> discoverApplicationAttributes(Map<String, Object> options) throws ConnectorException {
		if (log.isDebugEnabled()) {
			log.debug(LogiPlexTools.stripControls(String.format("Enter: discoverApplicationAttributes(%)", options)));
		}
		return this.masterConnector.discoverApplicationAttributes(options);
	}

	/**
	 * Runtime exception to be thrown in case of an issue while iterating.
	 *
	 * @author menno.pieters
	 */
	public class LogiPlexIteratorException extends RuntimeException {

		public LogiPlexIteratorException(Throwable e) {
			super(e);
		}

		/**
		 *
		 */
		private static final long serialVersionUID = -5924669785877532031L;
	}

	/**
	 * Special iterator class that allows for copying/splitting the
	 * ResourceObjects before returning them.
	 *
	 * @param <E>
	 * @author menno.pieters@sailpoint.com
	 */
	public class LogiPlexIterator<E> implements Iterator<ResourceObject>, CloseableIterator<ResourceObject> {

		public final static int MAX_FILL_RETRY = 10;
		private Map<String, Object> options = null;
		private final Logger log = Logger.getLogger(LogiPlexIterator.class.getName());
		private CloseableIterator<ResourceObject> masterIterator = null;
		private Queue<ResourceObject> queue = new ConcurrentLinkedQueue<ResourceObject>();
		private LogiPlexUtil util;
		private Connector masterConnector = null;
		private int objectCount = 0;
		private String objectType = null;

		/**
		 * Constructor
		 * 
		 * @param masterConnector
		 * @param masterIterator
		 * @param util
		 * @param options
		 * @throws GeneralException
		 */
		public LogiPlexIterator(String objectType, Connector masterConnector, CloseableIterator<ResourceObject> masterIterator, LogiPlexUtil util, Map<String, Object> options) throws GeneralException {
			super();
			if (log.isDebugEnabled()) {
				log.debug(LogiPlexTools.stripControls(String.format("Constructor: LogiPlexIterator(%s, %s)", masterIterator, util)));
			}
			if (masterIterator == null) {
				throw new GeneralException("Master iterator must not be null");
			}
			this.masterConnector = masterConnector;
			this.masterIterator = masterIterator;
			this.objectType = objectType;
			this.util = util;
			this.options = options;
		}

		/**
		 * Determine whether the aggregation is performed in delta mode (true)
		 * or not (false).
		 * 
		 * @return true if a delta aggregation is being performed.
		 */
		private boolean isDelta() {
			if (log.isDebugEnabled()) {
				log.debug("Enter: isDelta()");
			}
			boolean delta = false;
			if (options != null) {
				if (options.containsKey("deltaAggregation")) {
					delta = Util.otob(options.get("deltaAggregation"));
				}
			}
			if (log.isDebugEnabled()) {
				log.debug(String.format("Exit: isDelta(): %b", delta));
			}
			return delta;
		}

		/**
		 * Close the iterator and underlying master iterator.
		 */
		public void close() {
			if (log.isDebugEnabled()) {
				log.debug("Enter: close()");
			}
			if (log.isTraceEnabled()) {
				log.trace(String.format("Objects processed: %d", objectCount));
			}
			masterIterator.close();
		}

		/**
		 * Read the next item from the master iterator and run the split rule,
		 * if available. Add the results to the internal queue.
		 * 
		 * In delta iteration mode, it may have to try again a couple of times.
		 * At least the Active Directory DirSync iterator may sometimes report
		 * true for the hasNext() method, but still return null when next() is
		 * called. In that case, a new call must be made to hasNext() and
		 * next(), up until a maximum number of retries.
		 * 
		 * Possibly other connectors show similar behavior.
		 *
		 * @throws GeneralException
		 */
		private void fillQueue() throws GeneralException {
			if (log.isDebugEnabled()) {
				log.error("Enter: fillQueue()");
			}
			boolean hasNext = masterIterator.hasNext();
			if (!hasNext && isDelta()) {
				int tryCount = 1;
				while (!hasNext && tryCount <= MAX_FILL_RETRY) {
					if (log.isTraceEnabled()) {
						log.trace(String.format("No result in delta mode; retrying %d", tryCount));
					}
					hasNext = masterIterator.hasNext();
					tryCount++;
				}
			}
			if (log.isTraceEnabled()) {
				log.trace(String.format("fillQueue: hasNext: %b", hasNext));
			}
			if (hasNext) {
				ResourceObject object = masterIterator.next();
				// reference to MAX_FILL_RETRY = 10 property was removed as it may be easily exceeded with large number of changes handled by delta aggregation
				//There is similar logic in OOTB ConnectorProxy.peek() class, where we iterate until object != null or we reach end of iterator (without specifying max retries)
				while (hasNext && object == null) {
					/*
					 * The master iterator indicated that more objects are
					 * available, but no object was returned. We need to try
					 * again.
					 */
					if (log.isTraceEnabled()) {
						log.trace("fillQueue: null object returned");
					}
					hasNext = masterIterator.hasNext();
					if (hasNext) {
						object = masterIterator.next();
					}
				}
				objectCount++;
				if (object != null) {
					/*
					 * We go an object. Now we need to run this object through
					 * the split rule.
					 */
					if (log.isTraceEnabled()) {
						log.trace("fillQueue: " + object.getIdentity());
					}
					// Fill object type if not set by master iterator
					if (object.getObjectType() == null) {
						object.setObjectType(this.objectType);
					}
					// Check for incomplete or incremental objects (typically during delta)
					if ((object.isIncomplete() || object.isIncremental()) && masterConnector != null) {
						/*
						 * During delta aggregation, we may get an incomplete or incremental
						 * object, containing only the attributes that have been
						 * updated.
						 */
						if (log.isDebugEnabled()) {
							log.debug("Incomplete - getting full object: "+object.toXml());
						}
						try {
							ResourceObject tempObject = masterConnector.getObject(object.getObjectType(), object.getIdentity(), new HashMap<String, Object>());
							if (tempObject != null) {
								object = tempObject;
							}
						} catch (ConnectorException e) {
							log.error(e);
						}
						if (log.isTraceEnabled()) {
							log.trace("fillQueue: full object:" + object.toXml());
						}
					}
					Map<String, ResourceObject> map = util.splitResourceObject(object);
					List<ResourceObject> list = util.mapToList(map);
					queue.addAll(list);
				} else {
					log.warn("fillQueue: null object returned");
				}
			} else {
				log.warn("fillQueue: No more results.");
			}
		}

		/**
		 * Check whether more objects are available for aggregation. First check
		 * whether the internal queue has more objects. If so return true. If
		 * not, try to fill the queue with objects from the master iterator.
		 * Then return true if the queue has new objects.
		 * 
		 * @return true if there are more objects available
		 */
		public boolean hasNext() {
			if (log.isDebugEnabled()) {
				log.debug("Enter: hasNext()");
			}
			if (!queue.isEmpty()) {
				log.trace("Internal queue still has items");
				return true;
			}
			if (log.isTraceEnabled()) {
				log.trace("Try master iterator");
			}
			try {
				fillQueue();
			} catch (GeneralException e) {
				log.error(e);
				throw new LogiPlexIteratorException(e);
			}
			if (log.isTraceEnabled()) {
				log.trace(LogiPlexTools.stripControls(String.format("hasNext(): %b", !queue.isEmpty())));
			}
			return !queue.isEmpty();
		}

		/**
		 * Return the next object from the internal queue. If there are no more
		 * elements, first try to fill the queue with objects from the master
		 * iterator, processed by the split rule.
		 * 
		 * @return a resource object for the main or a sub-application
		 */
		public ResourceObject next() {
			if (log.isDebugEnabled()) {
				log.debug("Enter: next()");
			}
			if (queue.isEmpty()) {
				log.debug("Queue is empty. Try to refill queue");
				try {
					fillQueue();
				} catch (GeneralException e) {
					log.error(e);
					throw new LogiPlexIteratorException(e);
				}
			}
			ResourceObject object = queue.poll();
			try {
				if (object != null) {
					if (log.isTraceEnabled()) {
						log.trace(object.toXml());
					}
				} else {
					log.warn("null object");
				}
			} catch (GeneralException e) {
				log.error(e);
			}
			return object;
		}
	}

	/**
	 * Utility class to support the LogiPlex connector and iterator.
	 *
	 * @author menno.pieters@sailpoint.com
	 */
	public class LogiPlexUtil {

		private final Logger log = Logger.getLogger(LogiPlexUtil.class.getName());
		private Attributes<String, Object> settings = null;
		private String applicationName = null;
		private SailPointContext context;
		private Map<String, Object> state = new HashMap<String, Object>();
		private Rule splitRule = null;

		/**
		 * Constructor.
		 *
		 * @param context
		 *            The SailPointContext.
		 * @param applicationName
		 *            The name of the LogiPlex Application
		 * @param settings
		 *            The settings for the application.
		 * @throws GeneralException
		 */
		public LogiPlexUtil(SailPointContext context, String applicationName, Attributes<String, Object> settings) throws GeneralException {
			if (log.isTraceEnabled()) {
				log.trace(LogiPlexTools.stripControls(String.format("Constructor: LogiPlexUtil(Context, %s, %s)", applicationName, settings)));
			}
			if (settings == null) {
				throw new GeneralException("No settings supplied");
			}
			if (applicationName == null) {
				throw new GeneralException("No application name supplied");
			}
			this.context = context;
			this.settings = settings;
			this.applicationName = applicationName;
		}

		/**
		 * Get the split rule from the LogiPlex application definition.
		 *
		 * @return The rule configured to split the retrieved ResourceObject.
		 *         Null if none is configured.
		 * @throws GeneralException
		 */
		private Rule getSplitRule() throws GeneralException {
			if (log.isTraceEnabled()) {
				log.trace("Enter: getSplitRule");
			}
			if (splitRule == null) {
				String ruleName = settings.getString(LOGIPLEX_AGGREGATION_RULE);
				if (ruleName != null) {
					splitRule = context.getObjectByName(Rule.class, ruleName);
					if (splitRule == null) {
						log.error(LogiPlexTools.stripControls(String.format("Rule %s not found.", ruleName)));
					}
				}
			}
			return splitRule;
		}

		/**
		 * Convert a map of application names and resource objects to a List.
		 *
		 * @param map
		 *            The Map to be converted to a List object
		 * @return A list of ResourceObjects.
		 */
		public List<ResourceObject> mapToList(Map<String, ResourceObject> map) {
			if (log.isTraceEnabled()) {
				log.trace(LogiPlexTools.stripControls(String.format("Enter: mapToList(%s)", map)));
			}
			String prefix = settings.getString(LOGIPLEX_PREFIX);
			return mapToList(map, prefix);
		}

		/**
		 * Convert a map of application names and resource objects to a List.
		 * Prefix the application names, if configured to do so and set the
		 * IIQSourceApplication attribute on the ResourceObject, if not yet set.
		 *
		 * @param map
		 *            The Map to be converted to a List object.
		 * @param prefix
		 *            The prefix to be applied to any application name in the
		 *            map.
		 * @return A list of ResourceObjects.
		 */
		public List<ResourceObject> mapToList(Map<String, ResourceObject> map, String prefix) {
			if (log.isTraceEnabled()) {
				log.trace(LogiPlexTools.stripControls(String.format("Enter: mapToList(%s, %s)", map, prefix)));
			}
			List<ResourceObject> results = new ArrayList<ResourceObject>();
			if (map != null) {
				for (String key : map.keySet()) {
					ResourceObject object = map.get(key);
					if (!key.equals(applicationName)) {
						String resourceName = object.getString("IIQSourceApplication");
						if (Util.isNullOrEmpty(resourceName)) {
							log.info("Split rule has not set IIQSourceApplication - using key and prefix to generate");
							resourceName = "";
							if (Util.isNotNullOrEmpty(prefix)) {
								resourceName += prefix;
								if (!prefix.endsWith("-")) {
									resourceName += "-";
								}
							}
							resourceName += key;
							log.info(LogiPlexTools.stripControls(String.format("Setting IIQSourceApplication: %s", resourceName)));
							object.put("IIQSourceApplication", resourceName);
						}
					} else {
						// Skip if application equals main application.
					}
					results.add(object);
				}
			}
			return results;
		}

		/**
		 * Update a map of lists.
		 *
		 * @param map
		 *            The map to be updated. If null, a new map will be created.
		 * @param key
		 *            The key for the List to be updated.
		 * @param value
		 *            The value to be added to the List in the map.
		 * @return An updated map with List objects for each key.
		 */
		public Map<String, List<Object>> updateListMap(Map<String, List<Object>> map, String key, Object value) {
			if (log.isTraceEnabled()) {
				log.trace(LogiPlexTools.stripControls(String.format("Enter: updateListMap(%s, %s, %s)", map, key, value)));
			}
			if (map == null) {
				map = new HashMap<String, List<Object>>();
			}
			if (key != null) {
				List<Object> list = map.get(key);
				if (list == null) {
					list = new ArrayList<Object>();
				}
				if (value != null && !list.contains(value)) {
					list.add(value);
				}
				map.put(key, list);
			}
			return map;
		}
		
		/**
		 * Get a Link representing the account matching the input parameters.
		 * 
		 * @param applicationName
		 * @param instance
		 * @param nativeIdentity
		 * @return a Link object if found.
		 */
		private Link getAccountLink(String applicationName, String instance, String nativeIdentity) {
			if (Util.isNotNullOrEmpty(applicationName) && Util.isNotNullOrEmpty(nativeIdentity)) {
				List<Filter> filters = new ArrayList<Filter>();
				filters.add(Filter.eq("application.name", applicationName));
				if (Util.isNotNullOrEmpty(instance)) {
					filters.add(Filter.eq("instance", instance));
				}
				filters.add(Filter.eq("nativeIdentity", nativeIdentity));				
				try {
					return this.context.getUniqueObject(Link.class, Filter.and(filters));
				} catch (GeneralException e) {
					log.warn(String.format("Unable to find unique account on application %s with name %s", applicationName, nativeIdentity), e);
					if (log.isDebugEnabled()) {
						e.printStackTrace();
					}
				}
			}
			return null;
		}
		
		/**
		 * Get a Link representing the account matching the application name and resource object.
		 * 
		 * @param applicationName
		 * @param object
		 * @return a Link object if found.
		 */
		private Link getAccountLink(String applicationName, ResourceObject object) {
			return getAccountLink(applicationName, object.getInstance(), object.getIdentity());
		}
		
		private ManagedAttribute getManagedAttribute(String applicationName, String type, String instance, String nativeIdentity) {
			if (Util.isNotNullOrEmpty(applicationName) && Util.isNotNullOrEmpty(type) && Util.isNotNullOrEmpty(nativeIdentity)) {
				List<Filter> filters = new ArrayList<Filter>();
				filters.add(Filter.eq("application.name", applicationName));
				if (Util.isNotNullOrEmpty(instance)) {
					filters.add(Filter.eq("instance", instance));
				}
				filters.add(Filter.eq("type", type));				
				filters.add(Filter.eq("value", nativeIdentity));				
				try {
					return (ManagedAttribute)this.context.getUniqueObject(ManagedAttribute.class, Filter.and(filters));
				} catch (GeneralException e) {
					log.warn(String.format("Unable to find unique managed attribute object on application %s with type %s and name %s", applicationName, type, nativeIdentity), e);
					if (log.isDebugEnabled()) {
						e.printStackTrace();
					}
				}
			}
			return null;
		}
		
		private ManagedAttribute getManagedAttribute(String applicationName, ResourceObject object) {
			if (object != null) {
				return getManagedAttribute(applicationName, object.getObjectType(), object.getInstance(), object.getIdentity());
			}
			return null;
		}

		/**
		 * Split the ResourceObject into separate items per application.
		 *
		 * @param object
		 *            The ResourceObject to be inspected and split.
		 * @return A Map of application names and ResourceObjects.
		 * @throws GeneralException
		 */
		@SuppressWarnings("unchecked")
		public Map<String, ResourceObject> splitResourceObject(ResourceObject object) throws GeneralException {
			if (log.isTraceEnabled()) {
				log.trace(LogiPlexTools.stripControls(String.format("Enter: splitResourceObject(%s)", object)));
			}
			Map<String, ResourceObject> results = new HashMap<String, ResourceObject>();
			
			if (object.isDelete()) {
				if (log.isDebugEnabled()) {
					log.debug("Delete event: using default logic for deletions");
				}
				if ("account".equals(object.getObjectType())) {
					if (log.isDebugEnabled()) {
						log.debug("Account deletion: look up sub-accounts and add a delete event for these objects, too.");
					}
					results.put(applicationName, object);
					Link mainLink = getAccountLink(this.applicationName, object);
					if (mainLink != null) {
						List<String> subApplicationNames = LogiPlexTools.getSubApplicationNames(context, context.getObjectByName(Application.class, applicationName));
						if (subApplicationNames != null && !subApplicationNames.isEmpty()) {
							for (String subApplicationName: subApplicationNames) {
								Link subLink = getAccountLink(subApplicationName, object);
								if (subLink != null) {
									ResourceObject subObject = (ResourceObject) object.deepCopy(context);
									results.put(subApplicationName, subObject);
								}
							}
						}
					}
				} else {
					if (log.isDebugEnabled()) {
						log.debug("Group deletion: look up managed attributes for main and sub-applications and add a delete event for these objects, too.");
					}
					List<String> applicationNames = new ArrayList<String>();
					applicationNames.add(applicationName);
					List<String> subApplicationNames = LogiPlexTools.getSubApplicationNames(context, context.getObjectByName(Application.class, applicationName));
					if (subApplicationNames != null && !subApplicationNames.isEmpty()) {
						applicationNames.addAll(subApplicationNames);
					}
					for (String name: applicationNames) {
						ManagedAttribute ma = getManagedAttribute(applicationName, object);
						if (ma != null) {
							ResourceObject subObject = (ResourceObject) object.deepCopy(context);
							results.put(name, subObject);
						}
					}
				}
			} else {
				Rule rule = getSplitRule();
				if (rule != null) {
					Map<String, Object> args = new HashMap<String, Object>();
					args.put("context", context);
					args.put("log", log);
					args.put("object", object);
					args.put("state", state);
					args.put("applicationName", applicationName);
					args.put("application", context.getObjectByName(Application.class, applicationName));
					args.put("map", new HashMap<String, Object>());
					args.put("util", this);
					Object result = context.runRule(getSplitRule(), args);
					if (result instanceof Map) {
						results.putAll((Map<String, ResourceObject>) result);
					} else if (result != null) {
						throw new GeneralException("Unsupported return type: expected a Map<String, ResourceObject>.");
					}
				} else {
					log.warn("No split rule defined, adding object to default application name.");
					results.put(this.applicationName, object);
				}
			}
			return results;
		}
	}

	/**
	 * Static class with additional helper methods.
	 *
	 * @author menno.pieters
	 */
	public static class LogiPlexTools {

		private final static Logger log = Logger.getLogger(LogiPlexTools.class.getName());

		/**
		 * Get the main application. If the application has a proxy, return the
		 * proxy, otherwise the application is considered the main application.
		 *
		 * @param application
		 *            The application for which the main application must be
		 *            found.
		 * @return The main LogiPlex application.
		 */
		public static Application getMainApplication(Application application) {
			if (log.isTraceEnabled()) {
				log.trace(LogiPlexTools.stripControls(String.format("Enter: getMainApplication(%s)", application)));
			}
			if (application == null) {
				return null;
			}
			Application proxy = application.getProxy();
			if (proxy == null) {
				log.debug("No proxy, application is main application");
				return application;
			} else if (proxy.getType().equals(application.getType())) {
				log.debug(LogiPlexTools.stripControls(String.format("Found proxy application by type: %s", proxy)));
				return proxy;
			} else if (proxy.getConnector().equals(LogiPlexConnector.class.getName())) {
				log.debug(LogiPlexTools.stripControls(String.format("Found proxy application by connector class: %s", proxy)));
				return proxy;
			} else {
				log.debug("Proxy is not the same type, returning application");
				return application;
			}
		}

		/**
		 * Get a list of all application names that have the main application as
		 * proxy.
		 *
		 * @param context
		 *            The current SailPointContext.
		 * @param application
		 *            The main application
		 * @return A list of application names.
		 * @throws GeneralException
		 */
		public static List<String> getSubApplicationNames(SailPointContext context, Application application) throws GeneralException {
			if (log.isTraceEnabled()) {
				log.trace(LogiPlexTools.stripControls(String.format("Enter: getMainApplication(%s)", application)));
			}
			List<String> applicationNames = new ArrayList<String>();
			if (context != null && application != null) {
				application = getMainApplication(application);

				Filter filter = Filter.eq("proxy.name", application.getName());
				QueryOptions qo = new QueryOptions();
				qo.addFilter(filter);
				try {
					Iterator<Object[]> iterator = context.search(Application.class, qo, "name");
					while (iterator != null && iterator.hasNext()) {
						Object[] data = iterator.next();
						String name = (String) data[0];
						applicationNames.add(name);
					}
				} catch (GeneralException e) {
					log.error("Unable to retrieve sub-applications", e);
					throw e;
				}
			}
			return applicationNames;
		}

		/**
		 * Check whether the account request application is the main
		 * application.
		 *
		 * @param application
		 *            The application to be tested.
		 * @param req
		 *            The account request to be tested
		 * @return True if the request is for the main application, otherwise
		 *         false.
		 */
		public static boolean isMainApplicationRequest(Application application, AbstractRequest req) {
			if (log.isTraceEnabled()) {
				log.trace(LogiPlexTools.stripControls(String.format("Enter: isMainApplicationRequest(%s, %s)", application, req)));
			}
			Application mainApplication = getMainApplication(application);
			if (req != null && mainApplication != null) {
				return req.getApplication().equals(mainApplication.getName());
			}
			return false;
		}

		/**
		 * Get the Link object corresponding to the account request.
		 *
		 * @param context
		 *            The current SailPointContext.
		 * @param identity
		 * @param req
		 * @return
		 * @throws GeneralException
		 */
		public static Link getLink(SailPointContext context, Identity identity, AccountRequest req) throws GeneralException {
			if (log.isTraceEnabled()) {
				log.trace(LogiPlexTools.stripControls(String.format("Enter: getLink(%s, %s, %s)", context, identity, req)));
			}
			if (req != null) {
				String nativeIdentity = req.getNativeIdentity();
				if (nativeIdentity != null) {
					IdentityService service = new IdentityService(context);
					return service.getLink(identity, req.getApplication(context), req.getInstance(), nativeIdentity);
				}
			}
			return null;
		}

		/**
		 * Get the Link object for the main application, corresponding to the
		 * account request.
		 *
		 * @param context
		 * @param identity
		 * @param application
		 * @param req
		 * @return
		 * @throws GeneralException
		 */
		public static Link getMainLink(SailPointContext context, Identity identity, Application application, AccountRequest req) throws GeneralException {
			if (log.isTraceEnabled()) {
				log.trace(LogiPlexTools.stripControls(String.format("Enter: getMainLink(%s, %s, %s)", context, identity, application, req)));
			}
			if (req != null) {
				IdentityService service = new IdentityService(context);
				String nativeIdentity = req.getNativeIdentity();
				Link link = null;
				Application mainApplication = getMainApplication(application);
				if (mainApplication != null) {
					boolean linkFromMaster = Util.otob(mainApplication.getAttributeValue(LOGIPLEX_ATTR_LINK_FROM_MASTER));
					if (log.isTraceEnabled()) {
						log.trace(String.format("linkFromMaster: %b for main application %s", linkFromMaster, mainApplication.toString()));
					}
					if (linkFromMaster) {
						String master = (String) mainApplication.getAttributeValue(LOGIPLEX_ATTR_MASTER_APPLICATION);
						Application masterApplication = context.getObject(Application.class, master);
						if (masterApplication != null) {
							mainApplication = masterApplication;
							if (log.isTraceEnabled()) {
								log.trace(String.format("Master Application as Main: %s", master));
							}
						} else {
							throw new GeneralException(String.format("Unable to get master application for %s", mainApplication.getName()));
						}
					}
					// If the request has a selected account, use this to get
					// the main account
					if (Util.isNotNullOrEmpty(nativeIdentity)) {
						link = service.getLink(identity, mainApplication, req.getInstance(), nativeIdentity);
					}
					if (link != null) {
						return link;
					} else {
						log.warn(String.format("No corresponding account found on main application %s for nativeIdentity %s", mainApplication.getName(), nativeIdentity));
					}

					// No suitable link found for nativeIdentity
					List<Link> links = service.getLinks(identity, mainApplication, req.getInstance());
					if (links != null && !links.isEmpty()) {
						// If nativeIdentity is null, get first unused account.
						// This will work only for create operations and in part
						// for modify.
						if (Util.isNullOrEmpty(nativeIdentity)) {
							if (AccountRequest.Operation.Create.equals(req.getOperation())) {
								// Find the first unused, or return to create a
								// new account
								for (Link linkToCheck : Util.safeIterable(links)) {
									Link subLink = service.getLink(identity, application, linkToCheck.getInstance(), linkToCheck.getNativeIdentity());
									if (subLink != null) {
										if (log.isDebugEnabled()) {
											log.debug(String.format("Corresponding sub-account on %s found for main account %s; skipping", application.getName(), subLink.getNativeIdentity()));
										}
									} else {
										if (log.isDebugEnabled()) {
											log.debug(String.format("No corresponding sub-account on %s found for main account %s; suitable main account found.", application.getName(),
													linkToCheck.getNativeIdentity()));
										}
										return linkToCheck;
									}
								}
								// Still no corresponding account found - force
								// creation.
								log.info("No suitable main account found, return null to force account creation on main application");
								return null;
							} else {
								// 2019.02.21 - this should not be reached.
								// Currently this method is called only for a
								// Create operation.
								log.warn(String.format("Operation %s should specify an account, but no nativeIdentity found", req.getOperation()));
								if (AccountRequest.Operation.Modify.equals(req.getOperation())) {
									link = links.get(0);
									if (link != null) {
										log.warn(String.format("For operation %s selecting first found main/master account", req.getOperation()));
										return link;
									} else {
										log.error(String.format("No suitable link found for operation %s, returning null", req.getOperation()));
										return null;
									}
								} else {
									log.warn(String.format("Ignoring operation %s for null identityRequest", req.getOperation()));
								}
							}
						} else {
							// Added multiple accounts handling.
							//
							// This point will only be reached if there is a
							// selected account and no corresponding main
							// account
							// can be found. This loop will try to compare based
							// on other attributes.
							Link subLink = service.getLink(identity, application, req.getInstance(), nativeIdentity);
							if (subLink == null) {
								log.error(String.format("Request for native identity %s. No sub-account found on %s. Cannot get corresponding main account. Returning null.", nativeIdentity,
										application.getName()));
								return null;
							}
							// Try to find main account based on sub-account's
							// display name or UUID value.
							String subLinkDisplayName = subLink.getDisplayableName();
							String subLinkUuid = subLink.getUuid();
							if (Util.isNullOrEmpty(subLinkDisplayName) && Util.isNullOrEmpty(subLinkUuid)) {
								log.error(String.format(
										"Request for native identity %s. Sub-account found on %s, but does not have a display name or UUID. Cannot get corresponding main account. Returning null.",
										nativeIdentity, application.getName()));
								return null;
							}
							for (Link linkToCheck : Util.safeIterable(links)) {
								if (linkToCheck != null) {
									// Try UUID
									if (Util.nullSafeEq(linkToCheck.getUuid(), subLinkUuid)) {
										return linkToCheck;
									}
									// Try display name
									if (Util.nullSafeEq(linkToCheck.getDisplayableName(), subLinkDisplayName)) {
										return linkToCheck;
									}
								}
							}
						}
					}
				}
			}

			// Account request is null
			return null;
		}

		/**
		 * Given an application definition, get all the names of attributes that
		 * are marked as entitlements.
		 *
		 * @param application
		 * @return
		 */
		public static List<String> getAccountEntitlementAttributes(Application application) {
			List<String> attrs = new ArrayList<String>();
			if (application != null) {
				Schema schema = application.getAccountSchema();
				List<String> names = schema.getEntitlementAttributeNames();
				if (names != null && !names.isEmpty()) {
					attrs.addAll(names);
				}
			}
			return attrs;
		}

		/**
		 * Check whether the attribute is listed as an entitlement for the given
		 * application.
		 *
		 * @param application
		 * @param attributeName
		 * @return
		 */
		public static boolean isAccountEntitlementAttribute(Application application, String attributeName) {
			List<String> entitlementNames = getAccountEntitlementAttributes(application);
			return entitlementNames.contains(attributeName);
		}

		/**
		 * Check whether an application attribute is multi-valued.
		 *
		 * @param application
		 * @param attributeName
		 * @return
		 * @throws GeneralException
		 */
		public static boolean isMultiValuedAccountAttribute(Application application, String attributeName) throws GeneralException {
			if (application != null) {
				Schema schema = application.getAccountSchema();
				AttributeDefinition attributeDefinition = schema.getAttributeDefinition(attributeName);
				if (attributeDefinition != null) {
					return attributeDefinition.isMultiValued();
				}
				throw new GeneralException("Attribute not found");
			}
			throw new GeneralException("Application is not set");
		}

		/**
		 * Compare an old and new list of objects and return those that are in
		 * the old list, but not in the new list.
		 *
		 * @param oldList
		 * @param newList
		 * @return
		 */
		public static List<?> getItemsToRemove(List<?> oldList, List<?> newList) {
			if (oldList == null || oldList.isEmpty()) {
				// Empty List.
				return new ArrayList<Object>();
			}
			List<Object> toRemove = new ArrayList<Object>();
			if (newList == null || newList.isEmpty()) {
				// New list is empty, remove all old items
				toRemove.addAll(oldList);
			} else {
				// Add items that are not in the new list to the list of items
				// to be removed.
				for (Object o : oldList) {
					if (!newList.contains(o)) {
						toRemove.add(o);
					}
				}
			}
			return toRemove;
		}

		/**
		 * Compare and old and new list of objects and return those that are in
		 * the new list, but not in the old list.
		 *
		 * @param oldList
		 * @param newList
		 * @return
		 */
		public static List<?> getItemsToAdd(List<?> oldList, List<?> newList) {
			if (newList == null || newList.isEmpty()) {
				// Empty List. Nothing to add/
				return new ArrayList<Object>();
			}
			List<Object> toAdd = new ArrayList<Object>();
			if (oldList == null || oldList.isEmpty()) {
				// New list is empty, remove all old items
				toAdd.addAll(newList);
			} else {
				// Add items that are not in the old list to the list of items
				// to be added.
				for (Object o : newList) {
					if (!oldList.contains(o)) {
						toAdd.add(o);
					}
				}
			}
			return toAdd;
		}

		/**
		 * Take the provisioning results from the provisioned plan and update
		 * the originally requested plan with these results.
		 * 
		 * @param originalPlan
		 * @param accountRequest
		 */
		public static void updateResultsInOriginalPlan(ProvisioningPlan originalPlan, AccountRequest accountRequest) {
			if (originalPlan != null && accountRequest != null) {
				AccountRequest matchingAccountRequest = getMatchingAccountRequest(originalPlan, accountRequest);
				if (matchingAccountRequest != null) {
					matchingAccountRequest.setResult(accountRequest.getResult());
				}
			}
		}

		/**
		 * For a given account request, find the corresponding account request
		 * in the originally requested plan.
		 * 
		 * @param originalPlan
		 * @param accountRequest
		 * @return
		 */
		public static AccountRequest getMatchingAccountRequest(ProvisioningPlan originalPlan, AccountRequest accountRequest) {
			if (originalPlan != null && accountRequest != null) {
				String trackingId = (String) accountRequest.getArgument(LOGIPLEX_TRACKING_ID);
				if (Util.isNotNullOrEmpty(trackingId)) {
					List<AccountRequest> originalPlanAccountRequests = originalPlan.getAccountRequests();
					for (AccountRequest originalPlanAccountRequest : originalPlanAccountRequests) {
						if (originalPlanAccountRequest != null) {
							String originalPlanAccountRequestTrackingId = (String) originalPlanAccountRequest.getArgument(LOGIPLEX_TRACKING_ID);
							if (Util.nullSafeEq(originalPlanAccountRequestTrackingId, trackingId)) {
								return originalPlanAccountRequest;
							}
						}
					}
				}
			}
			return accountRequest;
		}

		/**
		 * On each account request in the original plan, add a tracking ID
		 * (UUID) to the arguments map. This tracking ID is used later on, when
		 * trying to match the provisioned accounts and their original
		 * counterparts.
		 * 
		 * @param originalPlan
		 */
		public static void setTrackingIDOnOriginalPlan(ProvisioningPlan originalPlan) {
			List<AccountRequest> originalAccountRequests = originalPlan.getAccountRequests();
			for (AccountRequest originalAccountRequest : Util.safeIterable(originalAccountRequests)) {
				if (originalAccountRequest != null) {
					originalAccountRequest.put(LOGIPLEX_TRACKING_ID, UUID.randomUUID().toString());
				}
			}
		}
		
		/**
		 * String ASCII control characters, except for line endings.
		 * 
		 * @param input
		 * @return
		 */
		public static String stripControls(String input) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < input.length(); i++ ) {
				char c = input.charAt( i );
				if ( (c <= 0x20 && c != 0x0a && c != 0x0d) || (c >= 0x7f && c <= 0xa0) || (c ==  0xad) ) {
					sb.append(' ');
				} else {
					sb.append(c);
				}
			}
			return sb.toString();
		}
	}
}
