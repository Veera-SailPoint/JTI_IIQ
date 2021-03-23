package sailpoint.services.standard.task;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import sailpoint.api.SailPointContext;
import sailpoint.object.Application;
import sailpoint.object.AttributeSource;
import sailpoint.object.AttributeTarget;
import sailpoint.object.Attributes;
import sailpoint.object.Bundle;
import sailpoint.object.CorrelationConfig;
import sailpoint.object.Filter;
import sailpoint.object.Filter.MatchMode;
import sailpoint.object.Form;
import sailpoint.object.GenericConstraint;
import sailpoint.object.Identity;
import sailpoint.object.IdentitySelector;
import sailpoint.object.IdentitySelector.MatchTerm;
import sailpoint.object.IdentityTrigger;
import sailpoint.object.ObjectAttribute;
import sailpoint.object.ObjectConfig;
import sailpoint.object.Policy;
import sailpoint.object.QueryOptions;
import sailpoint.object.QuickLink;
import sailpoint.object.QuickLinkOptions;
import sailpoint.object.Rule;
import sailpoint.object.Script;
import sailpoint.object.TaskDefinition;
import sailpoint.object.Workflow;
import sailpoint.object.Workflow.Step;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;
import sailpoint.object.TaskResult;
import sailpoint.object.TaskSchedule;
import sailpoint.task.AbstractTaskExecutor;

/**
 * This task outputs a summary of the configuration of some of the common
 * IdentityIQ objects into an Excel spreadsheet.
 */

public class IdentityIQSummarizer extends AbstractTaskExecutor {
	private static Log logger = LogFactory.getLog(IdentityIQSummarizer.class);

	/**
	 * Path that we will output the file to
	 */
	public static final String ARG_OUTPUT_PATH = "outputPath";

	boolean terminate = false;
	String _outputPath;

	/**
	 * Main task execution method
	 */
	@SuppressWarnings("unchecked")
	public void execute(SailPointContext context, TaskSchedule schedule, TaskResult result,
			Attributes<String, Object> args) throws Exception {

		logInfoOutput("Start IdentityIQSummarizer");
		_outputPath = args.getString(ARG_OUTPUT_PATH);
		// Convert backslashes to forward slashes (will still work in Windows)
		_outputPath = _outputPath.replaceAll("\\\\", "/");
		if (!_outputPath.toLowerCase().endsWith(".xlsx"))
			_outputPath = _outputPath + ".xlsx";

		XSSFWorkbook workbook = new XSSFWorkbook();
		String pattern = "dd MMM yyyy HH:mm:ss z";
		SimpleDateFormat sdf = new SimpleDateFormat(pattern);
		logInfoOutput("Excel output file=" + _outputPath);
		PrintWriter fileOut = null;
		File tempOutputFile = null;
		boolean writeToConsolidatedCSVFile = false;
		try {
			// Set up output files
			if (writeToConsolidatedCSVFile) {
				tempOutputFile = new File(_outputPath);
				fileOut = new PrintWriter(tempOutputFile);
			}

			QueryOptions qo = new QueryOptions();

			List<String> ruleRefMaps = new ArrayList<String>();

			// Rules
			Iterator<Object[]> ruleIdsIterator = context.search(Rule.class, null, "id");
			if (ruleIdsIterator != null) {
				if (writeToConsolidatedCSVFile)
					fileOut.print(
							"Rule Name | Rule Type | Rule Description | Rule Created | Rule Modified  | Rule Source Lines \n");
				XSSFSheet sheetRules = workbook.createSheet("Rule");
				Object[][] datatypes = { { "Rule Name", "Rule Type", "Rule Description", "Rule Created",
						"Rule Modified", "Rule Source Lines" } };

				int rowNum = 0;
				logInfoOutput("Creating Excel sheet Rule");

				for (Object[] datatype : datatypes) {
					Row row = sheetRules.createRow(rowNum++);
					int colNum = 0;
					for (Object field : datatype) {
						Cell cell = row.createCell(colNum++);
						if (field instanceof String) {
							cell.setCellValue((String) field);
						} else if (field instanceof Integer) {
							cell.setCellValue((Integer) field);
						}
					}
				}

				while (ruleIdsIterator.hasNext()) {
					Object[] ruleIdArr = (Object[]) ruleIdsIterator.next();
					if (ruleIdArr != null && ruleIdArr.length == 1) {
						String ruleId = (String) ruleIdArr[0];
						if (ruleId != null) {
							Rule rule = context.getObjectById(Rule.class, ruleId);
							if (rule != null) {
								logInfoOutput("Rule Name=" + rule.getName());
								logInfoOutput("Rule Type=" + rule.getType());

								String description = rule.getDescription() == null ? ""
										: rule.getDescription().replaceAll("[\t\n\r]", "");

								logInfoOutput("Rule Description=" + description);
								logInfoOutput("Rule Created=" + rule.getCreated());
								logInfoOutput("Rule Modified=" + rule.getModified());
								logInfoOutput("Rule Source Lines=" + countLines(rule.getSource()));

								// ReferencedRules
								if (null != rule.getReferencedRules()) {
									List<Rule> ruleLibs = rule.getReferencedRules();
									for (Rule ruleLib : ruleLibs) {
										if (null != ruleLib) {
											String ruleRefMapEntry = ruleLib.getName() + " | Referenced Rule Library | "
													+ rule.getName();
											ruleRefMaps.add(ruleRefMapEntry);
										}
									}
								}

								// write out to the files
								if (writeToConsolidatedCSVFile)
									fileOut.print(rule.getName() + " | " + rule.getType() + " | " + description + " | "
											+ rule.getCreated() + " | " + rule.getModified() + " | "
											+ countLines(rule.getSource()) + "\n");

								Row row = sheetRules.createRow(rowNum++);
								int colNum = 0;

								Cell cell = row.createCell(colNum++);
								cell.setCellValue((String) rule.getName());
								cell = row.createCell(colNum++);
								if (null != rule.getType())
									cell.setCellValue((String) rule.getType().name());
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								cell.setCellValue((String) description);
								cell = row.createCell(colNum++);
								if (null != rule.getCreated())
									cell.setCellValue((String) sdf.format(rule.getCreated()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								if (null != rule.getModified())
									cell.setCellValue((String) sdf.format(rule.getModified()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);

								cell.setCellValue((Integer) countLines(rule.getSource()));
								cell = row.createCell(colNum++);

								context.decache(rule);
							}
						}
					}
				}
			}
			Util.flushIterator(ruleIdsIterator);

			// Workflows
			qo.addFilter(Filter.like("name", "Workflow", MatchMode.START));

			Iterator<Object[]> wfIdsIterator = context.search(Workflow.class, null, "id");
			if (wfIdsIterator != null) {
				// write out to the files
				if (writeToConsolidatedCSVFile)
					fileOut.print(
							"Workflow Name | Workflow Type | Workflow Description | Workflow Created | Workflow Modified  | Workflow Source Lines \n");

				XSSFSheet sheetWorkFlows = workbook.createSheet("Workflow");
				Object[][] datatypes = { { "Workflow Name", "Workflow Type", "Workflow Description", "Workflow Created",
						"Workflow Modified ", "Workflow Source Lines" } };

				int rowNum = 0;
				logInfoOutput("Creating Excel sheet Workflow");

				for (Object[] datatype : datatypes) {
					Row row = sheetWorkFlows.createRow(rowNum++);
					int colNum = 0;
					for (Object field : datatype) {
						Cell cell = row.createCell(colNum++);
						if (field instanceof String) {
							cell.setCellValue((String) field);
						} else if (field instanceof Integer) {
							cell.setCellValue((Integer) field);
						}
					}
				}
				while (wfIdsIterator.hasNext()) {
					Object[] wfIdArr = (Object[]) wfIdsIterator.next();
					if (wfIdArr != null && wfIdArr.length == 1) {
						String wfId = (String) wfIdArr[0];
						if (wfId != null) {
							Workflow workflow = context.getObjectById(Workflow.class, wfId);
							if (workflow != null) {

								logInfoOutput("Workflow Name=" + workflow.getName());
								logInfoOutput("Workflow Type=" + workflow.getType());

								String description = workflow.getDescription() == null ? ""
										: workflow.getDescription().replaceAll("[\t\n\r]", "");

								logInfoOutput("Workflow Description=" + description);
								logInfoOutput("Workflow Created=" + workflow.getCreated());
								logInfoOutput("Workflow Modified=" + workflow.getModified());

								Iterator<Step> iterSteps = workflow.getSteps().iterator();
								int workflowSourceLines = 0;
								while (iterSteps.hasNext()) {
									Step step = iterSteps.next();
									if (null != step && null != step.getScript()
											&& null != step.getScript().getSource())
										workflowSourceLines = workflowSourceLines
												+ countLines(step.getScript().getSource());
								}
								logInfoOutput("Workflow Source Lines=" + String.valueOf(workflowSourceLines));

								if (null != workflow.getRuleLibraries()) {
									List<Rule> ruleLibs = workflow.getRuleLibraries();
									for (Rule ruleLib : ruleLibs) {
										if (null != ruleLib) {
											String ruleRefMapEntry = ruleLib.getName()
													+ " | WorkFlow Rule Library Reference | " + workflow.getName();
											ruleRefMaps.add(ruleRefMapEntry);
										}
									}
								}
								// write out to the files
								if (writeToConsolidatedCSVFile)
									fileOut.print(workflow.getName() + " | " + workflow.getType() + " | " + description
											+ " | " + workflow.getCreated() + " | " + workflow.getModified() + " | "
											+ String.valueOf(workflowSourceLines) + "\n");

								Row row = sheetWorkFlows.createRow(rowNum++);
								int colNum = 0;

								Cell cell = row.createCell(colNum++);
								cell.setCellValue((String) workflow.getName());
								cell = row.createCell(colNum++);
								if (null != workflow.getType())
									cell.setCellValue((String) workflow.getType());
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								cell.setCellValue((String) description);
								cell = row.createCell(colNum++);
								if (null != workflow.getCreated())
									cell.setCellValue((String) sdf.format(workflow.getCreated()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								if (null != workflow.getModified())
									cell.setCellValue((String) sdf.format(workflow.getModified()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);

								cell.setCellValue((Integer) workflowSourceLines);
								cell = row.createCell(colNum++);

								context.decache(workflow);
							}
						}
					}
				}
			}
			Util.flushIterator(wfIdsIterator);

			// Applications

			qo = new QueryOptions();
			qo.addFilter(Filter.like("name", "Active", MatchMode.START));
			// Passing null as query options to fetch all
			Iterator<Object[]> appIdsIterator = context.search(Application.class, null, "id");
			if (appIdsIterator != null) {
				if (writeToConsolidatedCSVFile)
					fileOut.print(
							"Application Name | Application Type | Application Description | Application Created | Application Modified  | Application Proxy | Application URL \n");
				// write out to the files

				XSSFSheet sheetApplications = workbook.createSheet("Application");
				Object[][] datatypes = { { "Application Name", "Application Type", "Application Description",
						"Application Created", "Application Modified ", "Application Proxy", "Application URL" } };

				int rowNum = 0;
				logInfoOutput("Creating Excel sheet Application");

				for (Object[] datatype : datatypes) {
					Row row = sheetApplications.createRow(rowNum++);
					int colNum = 0;
					for (Object field : datatype) {
						Cell cell = row.createCell(colNum++);
						if (field instanceof String) {
							cell.setCellValue((String) field);
						} else if (field instanceof Integer) {
							cell.setCellValue((Integer) field);
						}
					}
				}
				while (appIdsIterator.hasNext()) {
					Object[] appIdArr = (Object[]) appIdsIterator.next();
					if (appIdArr != null && appIdArr.length == 1) {
						String appId = (String) appIdArr[0];
						if (appId != null) {
							Application app = context.getObjectById(Application.class, appId);
							if (app != null) {
								logInfoOutput("Application Name=" + app.getName());
								logInfoOutput("Application Type=" + app.getType());

								Attributes<String, Object> appAttrs = app.getAttributes();
								// TODO Read locale for fetching sys descriptions
								String description = "";
								if (appAttrs != null && appAttrs.get("sysDescriptions") != null
										&& ((Map<String, Object>) appAttrs.get("sysDescriptions")).get("en_US") != null)
									description = ((String) ((Map<String, Object>) appAttrs.get("sysDescriptions"))
											.get("en_US")).replaceAll("[\t\n\r]", "");
								logInfoOutput("Application Description=" + description);
								logInfoOutput("Application Created=" + app.getCreated());
								logInfoOutput("Application Modified=" + app.getModified());
								String appProxy = app.getProxy() == null ? "" : app.getProxy().getName();
								logInfoOutput("Application Proxy=" + appProxy);
								String appUrl = "";
								if (appAttrs != null && appAttrs.get("url") != null)
									appUrl = ((String) appAttrs.get("url"));

								logInfoOutput("Application URL=" + appUrl);

								if (null != app.getCorrelationRule()) {
									String ruleRefMapEntry = app.getCorrelationRule().getName()
											+ " | Application Correlation | " + app.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}

								if (null != app.getCustomizationRule()) {
									String ruleRefMapEntry = app.getCustomizationRule().getName()
											+ " | Application Customization | " + app.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}

								if (null != app.getCreationRule()) {
									String ruleRefMapEntry = app.getCreationRule().getName()
											+ " | Application Creation | " + app.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}
								if (null != app.getAfterProvisioningRule()
										&& !app.getAfterProvisioningRule().isEmpty()) {
									String ruleRefMapEntry = app.getAfterProvisioningRule()
											+ " | Application After Provisioning Rule | " + app.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}
								if (null != app.getBeforeProvisioningRule()
										&& !app.getBeforeProvisioningRule().isEmpty()) {
									String ruleRefMapEntry = app.getBeforeProvisioningRule()
											+ " | Application Before Provisioning Rule | " + app.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}
								if (null != app.getFormPathRules() && !app.getFormPathRules().isEmpty()) {
									String ruleRefMapEntry = app.getFormPathRules()
											+ " | Application Form Path Rules | " + app.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}
								if (null != app.getManagerCorrelationRule()) {
									String ruleRefMapEntry = app.getManagerCorrelationRule().getName()
											+ " | Application Manager Correlation Rule | " + app.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}
								if (null != app.getNativeRules()) {
									List<String> nativeRules = app.getNativeRules();
									for (String nativeRule : nativeRules) {
										if (null != nativeRule && !nativeRule.isEmpty()) {
											String ruleRefMapEntry = nativeRule + " | Application Native Rule | "
													+ app.getName();
											ruleRefMaps.add(ruleRefMapEntry);
										}
									}
								}
								// TODO Optimize the code, wherever possible
								if (appAttrs != null && appAttrs.get("buildMapRule") != null) {
									String ruleRefMapEntry = ((String) appAttrs.get("buildMapRule"))
											+ " | Application Build Map Rule | " + app.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}
								if (appAttrs != null && appAttrs.get("formPathRules") != null) {
									if (appAttrs.get("formPathRules") instanceof java.lang.String) {
										String ruleRefMapEntry = ((String) appAttrs.get("formPathRules"))
												+ " | Application Form Path Rule | " + app.getName();
										ruleRefMaps.add(ruleRefMapEntry);
									} else if (appAttrs.get("formPathRules") instanceof List) {
										List<String> formPathRules = (List<String>) appAttrs.get("formPathRules");
										for (String formPathRule : formPathRules) {
											String ruleRefMapEntry = formPathRule + " | Application Form Path Rule | "
													+ app.getName();
											ruleRefMaps.add(ruleRefMapEntry);
										}
									}
								}
								if (appAttrs != null && appAttrs.get("preIterateRule") != null) {
									String ruleRefMapEntry = ((String) appAttrs.get("preIterateRule"))
											+ " | Application Pre Iterate Rule | " + app.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}

								if (null != app.getAccountCorrelationConfig()
										&& null != app.getAccountCorrelationConfig().getReferenceName()) {
									String ruleRefMapEntry = app.getAccountCorrelationConfig().getReferenceName()
											+ " | Application Account Correlation Config | " + app.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}
								if (null != app.getManagedAttributeCustomizationRule()) {
									String ruleRefMapEntry = app.getManagedAttributeCustomizationRule().getName()
											+ " | Application Managed Attribute Customization Rule | " + app.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}

								// TODO Field Value RuleRef

								// write out to the files
								if (writeToConsolidatedCSVFile)
									fileOut.print(app.getName() + " | " + app.getType() + " | " + description + " | "
											+ app.getCreated() + " | " + app.getModified() + " | " + appProxy + " | "
											+ appUrl + "\n");
								Row row = sheetApplications.createRow(rowNum++);
								int colNum = 0;

								Cell cell = row.createCell(colNum++);
								cell.setCellValue((String) app.getName());
								cell = row.createCell(colNum++);
								if (null != app.getType())
									cell.setCellValue((String) app.getType());
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								cell.setCellValue((String) description);
								cell = row.createCell(colNum++);
								if (null != app.getCreated())
									cell.setCellValue((String) sdf.format(app.getCreated()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								if (null != app.getModified())
									cell.setCellValue((String) sdf.format(app.getModified()));
								else
									cell.setCellValue("");

								cell = row.createCell(colNum++);
								cell.setCellValue((String) appProxy);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) appUrl);
								cell = row.createCell(colNum++);
								context.decache(app);

							}
						}
					}
				}
			}
			Util.flushIterator(appIdsIterator);

			// Task Definition
			logInfoOutput("Task Definitions Info");

			// Passing null as query options to fetch all
			Iterator<Object[]> taskIdsIterator = context.search(TaskDefinition.class, null, "id");
			if (taskIdsIterator != null) {
				if (writeToConsolidatedCSVFile)
					fileOut.print(
							"Task Name | Task Type | Task Description | Task Created | Task Modified  | Task Sequences | Task Applications | Task Rule Name \n");
				XSSFSheet sheetTaskDefs = workbook.createSheet("TaskDefinition");
				Object[][] datatypes = { { "Task Name", "Task Type", "Task Description", "Task Created",
						"Task Modified ", "Task Sequences", "Task Applications", "Task Rule Name" } };

				int rowNum = 0;
				logInfoOutput("Creating sheet TaskDefinitions");

				for (Object[] datatype : datatypes) {
					Row row = sheetTaskDefs.createRow(rowNum++);
					int colNum = 0;
					for (Object field : datatype) {
						Cell cell = row.createCell(colNum++);
						if (field instanceof String) {
							cell.setCellValue((String) field);
						} else if (field instanceof Integer) {
							cell.setCellValue((Integer) field);
						}
					}
				}
				while (taskIdsIterator.hasNext()) {
					Object[] taskIdArr = (Object[]) taskIdsIterator.next();
					if (taskIdArr != null) {
						String taskId = (String) taskIdArr[0];
						if (taskId != null) {
							TaskDefinition task = context.getObjectById(TaskDefinition.class, taskId);
							if (task != null) {
								logInfoOutput("Task Name=" + task.getName());
								logInfoOutput("Task Type=" + task.getType());

								String description = "";
								if (task.getDescription() != null)
									description = task.getDescription().replaceAll("[\t\n\r]", "");
								logInfoOutput("Task Description=" + description);
								logInfoOutput("Task Created=" + task.getCreated());
								logInfoOutput("Task Modified=" + task.getModified());

								Attributes<String, Object> atts = task.getArguments();

								String tasksList = "";

								if (null != atts) {
									Object tasksListObj = atts.get("taskList");
									if (tasksListObj instanceof java.lang.String)
										tasksList = (String) tasksListObj;

									else if (tasksListObj instanceof List)
										tasksList = Util.listToCsv((List<String>) tasksListObj);
								}

								logInfoOutput("Task Sequences=" + tasksList);

								String applications = "";
								if (null != atts) {
									Object appsObj = atts.get("applications");
									if (appsObj instanceof java.lang.String)
										applications = (String) appsObj;

									else if (appsObj instanceof List)
										applications = Util.listToCsv((List<String>) appsObj);
								}

								logInfoOutput("Task Applications=" + applications);
								logInfoOutput("Task Rule Name=" + atts.get("ruleName"));
								String ruleName = atts == null ? "" : (String) atts.get("ruleName");
								logInfoOutput("Task Rule Name=" + ruleName);

								if (null != ruleName) {
									String ruleRefMapEntry = ruleName + " | Task Rule | " + task.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}

								if (null != atts.get("accountGroupRefreshRule")
										&& !((String) atts.get("accountGroupRefreshRule")).isEmpty()) {
									String ruleRefMapEntry = atts.get("accountGroupRefreshRule")
											+ " | Task Account Grup Refresh Rule | " + task.getName();
									ruleRefMaps.add(ruleRefMapEntry);
								}
								// TODO InitializationRule

								// write out to the files
								if (writeToConsolidatedCSVFile)
									fileOut.print(task.getName() + " | " + task.getType() + " | " + description + " | "
											+ task.getCreated() + " | " + task.getModified() + " | " + tasksList + " | "
											+ applications + " | " + ruleName + "\n");
								Row row = sheetTaskDefs.createRow(rowNum++);
								int colNum = 0;

								Cell cell = row.createCell(colNum++);
								cell.setCellValue((String) task.getName());
								cell = row.createCell(colNum++);
								if (null != task.getType())
									cell.setCellValue((String) task.getType().name());
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								cell.setCellValue((String) description);
								cell = row.createCell(colNum++);
								if (null != task.getCreated())
									cell.setCellValue((String) sdf.format(task.getCreated()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								if (null != task.getModified())
									cell.setCellValue((String) sdf.format(task.getModified()));
								else
									cell.setCellValue("");

								cell = row.createCell(colNum++);
								cell.setCellValue((String) tasksList);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) applications);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) ruleName);
								cell = row.createCell(colNum++);
								context.decache(task);
							}
						}
					}
				}
			}
			Util.flushIterator(taskIdsIterator);
			// File Write - Rule References
			// TODO Scan Dynamic Scope for Rule References
			if (null != ruleRefMaps && !ruleRefMaps.isEmpty()) {
				if (writeToConsolidatedCSVFile)
					fileOut.print("Rule Name | Rule Reference Type | Reffered By \n");

				XSSFSheet sheetRuleReferences = workbook.createSheet("RuleReference");
				Object[][] datatypes = { { "Rule Name", "Rule Reference Type", "Reffered By" } };

				int rowNum = 0;
				logInfoOutput("Creating Excel sheet RuleReference");

				for (Object[] datatype : datatypes) {
					Row row = sheetRuleReferences.createRow(rowNum++);
					int colNum = 0;
					for (Object field : datatype) {
						Cell cell = row.createCell(colNum++);
						if (field instanceof String) {
							cell.setCellValue((String) field);
						} else if (field instanceof Integer) {
							cell.setCellValue((Integer) field);
						}
					}
				}

				for (String ruleRefEntry : ruleRefMaps) {
					logInfoOutput("Rule Ref Entry=" + ruleRefEntry);
					if (writeToConsolidatedCSVFile)
						fileOut.print(ruleRefEntry + "\n");
					String[] ruleReferences = ruleRefEntry.split("\\|");
					Row row = sheetRuleReferences.createRow(rowNum++);
					int colNum = 0;
					Cell cell = row.createCell(colNum++);
					cell.setCellValue((String) ruleReferences[0]);
					cell = row.createCell(colNum++);
					cell.setCellValue((String) ruleReferences[1]);
					cell = row.createCell(colNum++);
					cell.setCellValue((String) ruleReferences[2]);
					cell = row.createCell(colNum++);
				}
			}
			// Bundle Definitions
			logInfoOutput("Bundle Definitions Info");

			// Passing null as query options to fetch all
			Iterator<Object[]> bundleIdsIterator = context.search(Bundle.class, null, "id");
			if (bundleIdsIterator != null) {
				if (writeToConsolidatedCSVFile)
					fileOut.print(
							"Bundle Name | Bundle Type | Bundle Status | Bundle Description | Bundle Created | Bundle Modified  | Bundle Application Name | Bundle Owner | Bundle Requirements | Bundle Inheritance \n");
				XSSFSheet sheetBundles = workbook.createSheet("Bundle");
				Object[][] datatypes = { { "Bundle Name", "Bundle Type", "Bundle Status", "Bundle Description",
						"Bundle Created", "Bundle Modified ", "Bundle Application Name", "Bundle Owner",
						"Bundle Requirements", "Bundle Inheritance" } };

				int rowNum = 0;
				logInfoOutput("Creating Excel sheet Bundle");

				for (Object[] datatype : datatypes) {
					Row row = sheetBundles.createRow(rowNum++);
					int colNum = 0;
					for (Object field : datatype) {
						Cell cell = row.createCell(colNum++);
						if (field instanceof String) {
							cell.setCellValue((String) field);
						} else if (field instanceof Integer) {
							cell.setCellValue((Integer) field);
						}
					}
				}

				while (bundleIdsIterator.hasNext()) {
					Object[] bundleIdAttr = (Object[]) bundleIdsIterator.next();
					if (bundleIdAttr != null) {
						String bundleId = (String) bundleIdAttr[0];
						if (bundleId != null) {
							Bundle bundle = context.getObjectById(Bundle.class, bundleId);
							if (bundle != null) {
								logInfoOutput("Bundle Name=" + bundle.getName());
								logInfoOutput("Bundle Type=" + bundle.getType());
								String bundleStatus = bundle.isDisabled() ? "Disabled" : "Enabled";
								logInfoOutput("Bundle Status=" + bundleStatus);
								Attributes<String, Object> bundleAttrs = bundle.getAttributes();
								// TODO Read locale for fetching sys descriptions
								String description = "";
								if (bundleAttrs != null && bundleAttrs.get("sysDescriptions") != null
										&& ((Map<String, Object>) bundleAttrs.get("sysDescriptions"))
												.get("en_US") != null)
									description = ((String) ((Map<String, Object>) bundleAttrs.get("sysDescriptions"))
											.get("en_US")).replaceAll("[\t\n\r]", "");

								logInfoOutput("Bundle Description=" + description);
								logInfoOutput("Bundle Created=" + bundle.getCreated());
								logInfoOutput("Bundle Modified=" + bundle.getModified());

								String bundleAppName = bundleAttrs == null ? "" : (String) bundleAttrs.get("appName");
								logInfoOutput("Bundle AppName=" + bundleAppName);

								String bundleOwnwer = (bundle.getOwner() == null) ? "" : bundle.getOwner().getName();
								logInfoOutput("Bundle Owner=" + bundleOwnwer);

								List<Bundle> bundleRequirementsList = bundle.getRequirements();
								StringBuffer bundleRequirements = new StringBuffer();
								for (Bundle bundleReq : bundleRequirementsList) {
									bundleRequirements.append(bundleReq.getName() + " ");
								}
								logInfoOutput("Bundle Requirements=" + bundleRequirements.toString());

								List<Bundle> bundleInheritanceList = bundle.getInheritance();
								StringBuffer bundleInheritance = new StringBuffer();
								for (Bundle bundleInh : bundleInheritanceList) {
									bundleInheritance.append(bundleInh.getName() + " ");
								}
								logInfoOutput("Bundle Inheritance=" + bundleInheritance.toString());

								// write out to the files
								if (writeToConsolidatedCSVFile)
									fileOut.print(bundle.getName() + " | " + bundle.getType() + " | " + bundleStatus
											+ " | " + description + " | " + bundle.getCreated() + " | "
											+ bundle.getModified() + " | " + bundleAppName + " | " + bundleOwnwer
											+ " | " + bundleRequirements.toString() + " | "
											+ bundleInheritance.toString() + "\n");
								Row row = sheetBundles.createRow(rowNum++);
								int colNum = 0;
								Cell cell = row.createCell(colNum++);
								cell.setCellValue((String) bundle.getName());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) bundle.getType());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) bundleStatus);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) description);
								cell = row.createCell(colNum++);
								if (null != bundle.getCreated())
									cell.setCellValue((String) sdf.format(bundle.getCreated()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								if (null != bundle.getModified())
									cell.setCellValue((String) sdf.format(bundle.getModified()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								cell.setCellValue((String) bundleAppName);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) bundleOwnwer);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) bundleRequirements.toString());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) bundleInheritance.toString());
								cell = row.createCell(colNum++);
								context.decache(bundle);
							}
						}
					}
				}
			}
			// Identity Trigger Definitions
			logInfoOutput("Identity Trigger Definitions Info");

			Iterator<Object[]> idyTriggerIdsIter = context.search(IdentityTrigger.class, null, "id");
			if (idyTriggerIdsIter != null) {
				if (writeToConsolidatedCSVFile)
					fileOut.print(
							"IdentityTrigger Name | IdentityTrigger Type | IdentityTrigger Status | IdentityTrigger Description | IdentityTrigger Created | IdentityTrigger Modified  | IdentityTrigger Handler WorkFlow | IdentityTrigger Rule | IdentityTrigger IdentitySelector \n");
				XSSFSheet sheetIdyTriggers = workbook.createSheet("IdentityTrigger");
				Object[][] datatypes = { { "IdentityTrigger Name", "IdentityTrigger Type", "IdentityTrigger Status",
						"IdentityTrigger Description", "IdentityTrigger Created", "IdentityTrigger Modified ",
						"IdentityTrigger Handler WorkFlow", "IdentityTrigger Rule",
						"IdentityTrigger IdentitySelector" } };

				int rowNum = 0;
				logInfoOutput("Creating Excel sheet IdentityTrigger");

				for (Object[] datatype : datatypes) {
					Row row = sheetIdyTriggers.createRow(rowNum++);
					int colNum = 0;
					for (Object field : datatype) {
						Cell cell = row.createCell(colNum++);
						if (field instanceof String) {
							cell.setCellValue((String) field);
						} else if (field instanceof Integer) {
							cell.setCellValue((Integer) field);
						}
					}
				}

				while (idyTriggerIdsIter.hasNext()) {
					Object[] idyTriggerIdAttr = (Object[]) idyTriggerIdsIter.next();
					if (idyTriggerIdAttr != null) {
						String idyTriggerId = (String) idyTriggerIdAttr[0];
						if (idyTriggerId != null) {
							IdentityTrigger idyTrigger = context.getObjectById(IdentityTrigger.class, idyTriggerId);
							if (idyTrigger != null) {
								logInfoOutput("IdentityTrigger Name=" + idyTrigger.getName());
								logInfoOutput("IdentityTrigger Type=" + idyTrigger.getType());
								String idyTriggerStatus = idyTrigger.isDisabled() ? "Disabled" : "Enabled";
								logInfoOutput("IdentityTrigger Status=" + idyTriggerStatus);

								String description = "";
								if (idyTrigger.getDescription() != null)
									description = idyTrigger.getDescription().replaceAll("[\t\n\r]", "");

								logInfoOutput("IdentityTrigger Description=" + description);
								logInfoOutput("IdentityTrigger Created=" + idyTrigger.getCreated());
								logInfoOutput("IdentityTrigger Modified=" + idyTrigger.getModified());

								String idyTriggerWFName = idyTrigger.getWorkflowName();
								logInfoOutput("IdentityTrigger WorkFlow Name=" + idyTriggerWFName);
								String idyTriggerRuleName = (idyTrigger.getRule() == null) ? ""
										: idyTrigger.getRule().getName();
								StringBuffer matchTermsStr = new StringBuffer();
								if (null != idyTrigger.getSelector()
										&& null != idyTrigger.getSelector().getMatchExpression()) {
									List<MatchTerm> matchTerms = idyTrigger.getSelector().getMatchExpression()
											.getTerms();

									for (MatchTerm matchTerm : matchTerms) {
										matchTermsStr.append(" Name=" + matchTerm.getName());
										matchTermsStr.append(" Value=" + matchTerm.getValue());
									}
									logInfoOutput("IdentityTrigger IdentitySelector=" + matchTermsStr.toString());
								}

								// write out to the files
								if (writeToConsolidatedCSVFile)
									fileOut.print(idyTrigger.getName() + " | " + idyTrigger.getType() + " | "
											+ idyTriggerStatus + " | " + description + " | " + idyTrigger.getCreated()
											+ " | " + idyTrigger.getModified() + " | " + idyTriggerWFName + " | "
											+ idyTriggerRuleName + " | " + matchTermsStr.toString() + "\n");
								Row row = sheetIdyTriggers.createRow(rowNum++);
								int colNum = 0;
								Cell cell = row.createCell(colNum++);
								cell.setCellValue((String) idyTrigger.getName());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) idyTrigger.getType().name());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) idyTriggerStatus);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) description);
								cell = row.createCell(colNum++);
								if (null != idyTrigger.getCreated())
									cell.setCellValue((String) sdf.format(idyTrigger.getCreated()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								if (null != idyTrigger.getModified())
									cell.setCellValue((String) sdf.format(idyTrigger.getModified()));
								else
									cell.setCellValue("");

								cell = row.createCell(colNum++);
								cell.setCellValue((String) idyTriggerWFName);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) idyTriggerRuleName);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) matchTermsStr.toString());
								cell = row.createCell(colNum++);

								context.decache(idyTrigger);
							}
						}
					}
				}
			}
			// CorrelationConfig Definitions
			logInfoOutput("CorrelationConfig Definitions Info");

			Iterator<Object[]> correlnCfgIdsIter = context.search(CorrelationConfig.class, null, "id");
			if (correlnCfgIdsIter != null) {
				if (writeToConsolidatedCSVFile)
					fileOut.print(
							"CorrelationConfig Name | CorrelationConfig Description | CorrelationConfig Status | CorrelationConfig Created | CorrelationConfig Modified  | CorrelationConfig AttributeAssignments \n");
				XSSFSheet sheetCorrelationConfig = workbook.createSheet("CorrelationConfig");
				Object[][] datatypes = { { "CorrelationConfig Name", "CorrelationConfig Description",
						"CorrelationConfig Status", "CorrelationConfig Created", "CorrelationConfig Modified ",
						"CorrelationConfig AttributeAssignments" } };

				int rowNum = 0;
				logInfoOutput("Creating Excel sheet CorrelationConfig");

				for (Object[] datatype : datatypes) {
					Row row = sheetCorrelationConfig.createRow(rowNum++);
					int colNum = 0;
					for (Object field : datatype) {
						Cell cell = row.createCell(colNum++);
						if (field instanceof String) {
							cell.setCellValue((String) field);
						} else if (field instanceof Integer) {
							cell.setCellValue((Integer) field);
						}
					}
				}

				while (correlnCfgIdsIter.hasNext()) {
					Object[] correlnCfgIdAttr = (Object[]) correlnCfgIdsIter.next();
					if (correlnCfgIdAttr != null) {
						String correlnCfgId = (String) correlnCfgIdAttr[0];
						if (correlnCfgId != null) {
							CorrelationConfig correlnCfg = context.getObjectById(CorrelationConfig.class, correlnCfgId);
							if (correlnCfg != null) {
								logInfoOutput("CorrelationConfig Name=" + correlnCfg.getName());
								String description = "";
								if (correlnCfg.getDescription() != null)
									description = correlnCfg.getDescription().replaceAll("[\t\n\r]", "");
								logInfoOutput("CorrelationConfig Description=" + description);

								String correlnCfgStatus = correlnCfg.isDisabled() ? "Disabled" : "Enabled";
								logInfoOutput("CorrelationConfig Status=" + correlnCfgStatus);

								logInfoOutput("CorrelationConfig Created=" + correlnCfg.getCreated());
								logInfoOutput("CorrelationConfig Modified=" + correlnCfg.getModified());

								List<Filter> filters = correlnCfg.getAttributeAssignments();

								StringBuffer filtersStr = new StringBuffer();
								for (Filter filter : filters) {
									filtersStr.append(" " + filter.toXml(false).replaceAll("[\t\n\r]", ""));
								}
								logInfoOutput("CorrelationConfig Filters=" + filtersStr.toString());

								// write out to the files
								if (writeToConsolidatedCSVFile)
									fileOut.print(correlnCfg.getName() + " | " + description + " | " + correlnCfgStatus
											+ " | " + correlnCfg.getCreated() + " | " + correlnCfg.getModified() + " | "
											+ filtersStr.toString() + "\n");
								Row row = sheetCorrelationConfig.createRow(rowNum++);
								int colNum = 0;
								Cell cell = row.createCell(colNum++);
								cell.setCellValue((String) correlnCfg.getName());
								cell = row.createCell(colNum++);

								cell.setCellValue((String) correlnCfgStatus);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) description);
								cell = row.createCell(colNum++);
								if (null != correlnCfg.getCreated())
									cell.setCellValue((String) sdf.format(correlnCfg.getCreated()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								if (null != correlnCfg.getModified())
									cell.setCellValue((String) sdf.format(correlnCfg.getModified()));
								else
									cell.setCellValue("");

								cell = row.createCell(colNum++);
								cell.setCellValue((String) filtersStr.toString());
								cell = row.createCell(colNum++);

								context.decache(correlnCfg);
							}
						}
					}
				}
			}

			// Policy Definitions
			logInfoOutput("Policy Definitions Info");
			// Passing null as query options to fetch all
			Iterator<Object[]> policyIdsIter = context.search(Policy.class, null, "id");
			if (policyIdsIter != null) {
				if (writeToConsolidatedCSVFile)
					fileOut.print(
							"Policy Name | Policy Type | Policy Description | Policy State | Policy Created | Policy Modified | Policy Owner | Policy Certification Actions |  Policy Violation Owner Type | Policy Alert Status | Policy Alert Escalation Style | Policy Constraint Details  \n");
				XSSFSheet sheetPolicies = workbook.createSheet("Policy");
				Object[][] datatypes = { { "Policy Name", "Policy Type", "Policy Description", "Policy State",
						"Policy Created", "Policy Modified", "Policy Owner", "Policy Certification Actions",
						" Policy Violation Owner Type", "Policy Alert Status", "Policy Alert Escalation Style",
						"Policy Constraint Details" } };

				int rowNum = 0;
				logInfoOutput("Creating Excel sheet Policy");

				for (Object[] datatype : datatypes) {
					Row row = sheetPolicies.createRow(rowNum++);
					int colNum = 0;
					for (Object field : datatype) {
						Cell cell = row.createCell(colNum++);
						if (field instanceof String) {
							cell.setCellValue((String) field);
						} else if (field instanceof Integer) {
							cell.setCellValue((Integer) field);
						}
					}
				}

				while (policyIdsIter.hasNext()) {
					Object[] policyIdAttr = (Object[]) policyIdsIter.next();
					if (policyIdAttr != null) {
						String policyId = (String) policyIdAttr[0];
						if (policyId != null) {
							Policy policy = context.getObjectById(Policy.class, policyId);
							if (policy != null) {
								logInfoOutput("Policy Name=" + policy.getName());
								logInfoOutput("Policy Type=" + policy.getType());
								// TODO Read locale for fetching sys descriptions
								String description = "";
								Attributes<String, Object> policyAttrs = policy.getAttributes();
								if (policyAttrs != null && policyAttrs.get("sysDescriptions") != null
										&& ((Map<String, Object>) policyAttrs.get("sysDescriptions"))
												.get("en_US") != null)
									description = ((String) ((Map<String, Object>) policyAttrs.get("sysDescriptions"))
											.get("en_US")).replaceAll("[\t\n\r]", "");

								logInfoOutput("Policy Description=" + description);

								String policyState = policy.getState().name();
								logInfoOutput("Policy State=" + policyState);

								logInfoOutput("Policy Created=" + policy.getCreated());
								logInfoOutput("Policy Modified=" + policy.getModified());

								String policyOwnwer = (policy.getOwner() == null) ? "" : policy.getOwner().getName();
								logInfoOutput("Policy Owner=" + policyOwnwer);
								String policyCertActions = policy.getCertificationActions();
								logInfoOutput("Policy Certification Actions=" + policyCertActions);
								String policyOwnerType = (policy.getViolationOwnerType() == null) ? ""
										: policy.getViolationOwnerType().name();
								logInfoOutput("Policy Owner Type=" + policyOwnerType);

								String policyAlertStatus = "";
								String policyAlertEscalationStyle = "";
								if (null != policy.getAlert()) {
									policyAlertStatus = policy.getAlert().isDisabled() ? "Disabled" : "Enabled";
									logInfoOutput("Policy Alert Status=" + policyAlertStatus);
									policyAlertEscalationStyle = policy.getAlert().getEscalationStyle();
									logInfoOutput("Policy Alert Escalation Style=" + policyAlertEscalationStyle);
								}

								List<GenericConstraint> policyConstraints = policy.getGenericConstraints();

								StringBuffer genericConstraintssStr = new StringBuffer();
								StringBuffer idySelectorStr = new StringBuffer();
								if (null != policyConstraints) {
									for (GenericConstraint policyConstraint : policyConstraints) {
										String policyConstrtViolnOwnType = "";
										if (null != policyConstraint.getViolationOwnerType()) {
											policyConstrtViolnOwnType = policyConstraint.getViolationOwnerType().name();

										}
										List<IdentitySelector> identitySelectors = policyConstraint.getSelectors();
										if (null != identitySelectors) {
											for (IdentitySelector identitySelector : identitySelectors) {
												if (null != identitySelector.getFilter()) {
													idySelectorStr.append(" Filter: " + identitySelector.getFilter()
															.toXml(false).replaceAll("[\t\n\r]", ""));
												}
												if (null != identitySelector.getMatchExpression()
														&& null != identitySelector.getMatchExpression().getTerms()) {
													List<MatchTerm> matchTerms = identitySelector.getMatchExpression()
															.getTerms();
													if (null != matchTerms) {
														for (MatchTerm matchTerm : matchTerms) {
															idySelectorStr.append(" MatchTerm: " + matchTerm
																	.toXml(false).replaceAll("[\t\n\r]", ""));
														}
													}
												}
												if (null != identitySelector.getPopulation()) {
													idySelectorStr.append(" Population: "
															+ identitySelector.getPopulation().getName());
												}
												if (null != identitySelector.getRule()) {
													idySelectorStr
															.append(" Rule: " + identitySelector.getRule().getName());
												}
												if (null != identitySelector.getScript()
														&& null != identitySelector.getScript().getSource()) {
													idySelectorStr.append(" Script: " + identitySelector.getScript()
															.getSource().replaceAll("[\t\n\r]", ""));
												}

											}
										}
										String policyConstrntDesc = (policyConstraint.getDescription() == null) ? ""
												: policyConstraint.getDescription().replaceAll("[\t\n\r]", "");
										genericConstraintssStr.append(" Name:" + policyConstraint.getName()
												+ " ViolationOwnerType:" + policyConstrtViolnOwnType + " Description: "
												+ policyConstrntDesc + " Identity Selector Details: " + idySelectorStr);
									}
								}
								logInfoOutput("Policy Constraint Details=" + genericConstraintssStr.toString());

								// write out to the files
								if (writeToConsolidatedCSVFile)
									fileOut.print(policy.getName() + " | " + policy.getType() + " | " + description
											+ " | " + policyState + " | " + policy.getCreated() + " | "
											+ policy.getModified() + " | " + policyOwnwer + " | " + policyCertActions
											+ " | " + policyOwnerType + " | " + policyAlertStatus + " | "
											+ policyAlertEscalationStyle + " | " + genericConstraintssStr.toString()
											+ "\n");
								Row row = sheetPolicies.createRow(rowNum++);
								int colNum = 0;
								Cell cell = row.createCell(colNum++);
								cell.setCellValue((String) policy.getName());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) policy.getType());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) description);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) policyState);
								cell = row.createCell(colNum++);

								if (null != policy.getCreated())
									cell.setCellValue((String) sdf.format(policy.getCreated()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								if (null != policy.getModified())
									cell.setCellValue((String) sdf.format(policy.getModified()));
								else
									cell.setCellValue("");

								cell = row.createCell(colNum++);
								cell.setCellValue((String) policyOwnwer);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) policyCertActions);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) policyOwnerType);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) policyAlertStatus);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) policyAlertEscalationStyle);
								cell = row.createCell(colNum++);

								logInfoOutput("Policy Constraint length=" + genericConstraintssStr.toString().length());
								if (null != genericConstraintssStr.toString()
										&& genericConstraintssStr.toString().length() > 32727) {
									int genConstrntLen = genericConstraintssStr.toString().length();
									int beginIndex = 0;
									while (genConstrntLen > 0) {
										logInfoOutput("beginIndex=" + beginIndex + " genConstrntLen=" + genConstrntLen);
										// Split properly and create additional cells
										if (genConstrntLen > 32727) {
											logInfoOutput("Policy Constraint Remaining Part="
													+ (String) genericConstraintssStr.toString().substring(beginIndex,
															beginIndex + 32726));
											cell.setCellValue((String) genericConstraintssStr.toString()
													.substring(beginIndex, beginIndex + 32726));
											genConstrntLen = genConstrntLen - 32726;
											beginIndex = beginIndex + 32726;
											cell = row.createCell(colNum++);
										} else {
											cell.setCellValue((String) genericConstraintssStr.toString()
													.substring(beginIndex, beginIndex + genConstrntLen));
											genConstrntLen = 0;
											break;
										}
									}
								} else {
									cell.setCellValue((String) genericConstraintssStr.toString());
								}
								cell = row.createCell(colNum++);

								context.decache(policy);
							}
						}
					}
				}
			} // End Policy Definitions

			// Quick Link Definitions
			logInfoOutput("Quick Links Info");
			// Passing null as query options to fetch all
			Iterator<Object[]> quickLinkIdsIter = context.search(QuickLink.class, null, "id");
			if (quickLinkIdsIter != null) {
				if (writeToConsolidatedCSVFile)
					fileOut.print(
							"QuickLink Name | QuickLink Category | QuickLink Action | QuickLink Description | QuickLink Created | QuickLink Modified | QuickLink Owner | QuickLink Arguments | QuickLink Options Details  \n");
				XSSFSheet sheetQuickLinks = workbook.createSheet("QuickLink");
				Object[][] datatypes = { { "QuickLink Name", "QuickLink Category", "QuickLink Action",
						"QuickLink Description", "QuickLink Created", "QuickLink Modified", "QuickLink Owner",
						"QuickLink Arguments", "QuickLink Options Details" } };

				int rowNum = 0;
				logInfoOutput("Creating Excel sheet QuickLink");

				for (Object[] datatype : datatypes) {
					Row row = sheetQuickLinks.createRow(rowNum++);
					int colNum = 0;
					for (Object field : datatype) {
						Cell cell = row.createCell(colNum++);
						if (field instanceof String) {
							cell.setCellValue((String) field);
						} else if (field instanceof Integer) {
							cell.setCellValue((Integer) field);
						}
					}
				}

				while (quickLinkIdsIter.hasNext()) {
					Object[] QuickLinkIdAttr = (Object[]) quickLinkIdsIter.next();
					if (QuickLinkIdAttr != null) {
						String QuickLinkId = (String) QuickLinkIdAttr[0];
						if (QuickLinkId != null) {
							QuickLink quickLink = context.getObjectById(QuickLink.class, QuickLinkId);
							if (quickLink != null) {
								logInfoOutput("QuickLink Name=" + quickLink.getName());
								logInfoOutput("QuickLink Category=" + quickLink.getCategory());
								logInfoOutput("QuickLink Action=" + quickLink.getAction());

								// TODO Read locale for fetching sys descriptions
								String description = (null == quickLink.getDescription()) ? ""
										: quickLink.getDescription().replaceAll("[\t\n\r]", "");

								logInfoOutput("QuickLink Description=" + description);

								logInfoOutput("QuickLink Created=" + quickLink.getCreated());
								logInfoOutput("QuickLink Modified=" + quickLink.getModified());

								String quickLinkOwnwer = (quickLink.getOwner() == null) ? ""
										: quickLink.getOwner().getName();
								logInfoOutput("QuickLink Owner=" + quickLinkOwnwer);
								Attributes<String, Object> quickLinkArgs = quickLink.getArguments();
								StringBuffer quickLinkAttributes = new StringBuffer();
								if (null != quickLinkArgs) {
									List<String> quickLinkAttrKeys = quickLinkArgs.getKeys();
									if (null != quickLinkAttrKeys) {
										for (String quickLinkAttrKey : quickLinkAttrKeys) {
											if (null != quickLinkArgs.get(quickLinkAttrKey) && quickLinkArgs
													.get(quickLinkAttrKey) instanceof sailpoint.object.Script)
												quickLinkAttributes.append(quickLinkAttrKey + "="
														+ ((Script) quickLinkArgs.get(quickLinkAttrKey)).getSource()
														+ " ");
											else
												quickLinkAttributes.append(quickLinkAttrKey + "="
														+ quickLinkArgs.get(quickLinkAttrKey) + " ");
										}
									}
								}

								// TODO Check the length of quickLinkAttributes and handle if it exceeds 32727
								// (cell limit)

								List<QuickLinkOptions> quickLinkOptions = null;
								
								// Before 7.1 there was no getQuickLinkOptions() method on QuickLink.
								// Use Reflection to check for presence of the method.
								Method getQuickLinkOptionsMethod = null;
								try {
									getQuickLinkOptionsMethod = QuickLink.class.getMethod("getQuickLinkOptions",
											(Class<?>[]) null);
								} catch (NoSuchMethodException e) {
									// getQuickLinkOptionsMethod will be null if the method doesn't exist.
								}
								if (null != getQuickLinkOptionsMethod) {
									quickLinkOptions = (List<QuickLinkOptions>) getQuickLinkOptionsMethod
											.invoke(quickLink, (Object[]) null);
								}

								StringBuffer qlOptionsDynScopeAttributes = new StringBuffer();

								StringBuffer quickLinkOptionsStr = new StringBuffer();

								if (null != quickLinkOptions) {
									for (QuickLinkOptions quickLinkOption : quickLinkOptions) {
										String qlOptionsDynScopeName = quickLinkOption.getDynamicScope().getName();
										logInfoOutput("qlOptionsDynScopeName=" + qlOptionsDynScopeName);
										Attributes<String, Object> quickLinkOptionsAttrs = quickLinkOption.getOptions();
										logInfoOutput("quickLinkOptionsAttrs=" + quickLinkOptionsAttrs);
										if (null != quickLinkOptionsAttrs) {
											List<String> quickLinkOptionsAttrsKeys = quickLinkOptionsAttrs.getKeys();

											if (null != quickLinkOptionsAttrsKeys) {
												for (String quickLinkOptionsAttrsKey : quickLinkOptionsAttrsKeys) {
													qlOptionsDynScopeAttributes.append(quickLinkOptionsAttrsKey + "="
															+ quickLinkOptionsAttrs.get(quickLinkOptionsAttrsKey)
															+ "  ");
												}
											}
										}
										quickLinkOptionsStr.append(qlOptionsDynScopeName + ":"
												+ qlOptionsDynScopeAttributes.toString() + "   ");

									}
								}
								logInfoOutput("quickLinkOptionsStr=" + quickLinkOptionsStr);

								// write out to the files
								if (writeToConsolidatedCSVFile)
									fileOut.print(quickLink.getName() + " | " + quickLink.getCategory() + " | "
											+ quickLink.getAction() + " | " + description + " | "
											+ quickLink.getCreated() + " | " + quickLink.getModified() + " | "
											+ quickLinkOwnwer + " | " + quickLinkAttributes.toString() + " | "
											+ quickLinkOptionsStr + "\n");
								Row row = sheetQuickLinks.createRow(rowNum++);
								int colNum = 0;
								Cell cell = row.createCell(colNum++);
								cell.setCellValue((String) quickLink.getName());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) quickLink.getCategory());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) quickLink.getAction());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) description);
								cell = row.createCell(colNum++);
								cell = row.createCell(colNum++);

								if (null != quickLink.getCreated())
									cell.setCellValue((String) sdf.format(quickLink.getCreated()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								if (null != quickLink.getModified())
									cell.setCellValue((String) sdf.format(quickLink.getModified()));
								else
									cell.setCellValue("");

								cell = row.createCell(colNum++);
								cell.setCellValue((String) quickLinkOwnwer);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) quickLinkAttributes.toString());
								cell = row.createCell(colNum++);

								CellStyle wrapStyle = workbook.createCellStyle();
								wrapStyle.setWrapText(true);

								cell.setCellStyle(wrapStyle);
								cell.setCellValue(quickLinkOptionsStr.toString().replaceAll("   ", "\r\n \r\n")
										.replaceAll(":", "\r\n"));
								sheetQuickLinks.autoSizeColumn(colNum);
								cell = row.createCell(colNum++);

								context.decache(quickLink);
							}
						}
					}
				}
			} // End of Quick Link Definitions

			// Form Definitions
			logInfoOutput("Form Info");
			// Passing null as query options to fetch all
			Iterator<Object[]> formIdsIter = context.search(Form.class, null, "id");
			if (formIdsIter != null) {
				if (writeToConsolidatedCSVFile)
					fileOut.print(
							"Form Name | Form Description | Form Type | Form Hidden | Form Created | Form Modified | Form Owner | Form Attributes |  Form Target User \n");
				XSSFSheet sheetForms = workbook.createSheet("Form");
				Object[][] datatypes = { { "Form Name", "Form Description", "Form Type", "Form Hidden", "Form Created",
						"Form Modified", "Form Owner", "Form Attributes", " Form Target User" } };

				int rowNum = 0;
				logInfoOutput("Creating Excel sheet Form");

				for (Object[] datatype : datatypes) {
					Row row = sheetForms.createRow(rowNum++);
					int colNum = 0;
					for (Object field : datatype) {
						Cell cell = row.createCell(colNum++);
						if (field instanceof String) {
							cell.setCellValue((String) field);
						} else if (field instanceof Integer) {
							cell.setCellValue((Integer) field);
						}
					}
				}

				while (formIdsIter.hasNext()) {
					Object[] formIdAttr = (Object[]) formIdsIter.next();
					if (formIdAttr != null) {
						String formId = (String) formIdAttr[0];
						if (formId != null) {
							Form form = context.getObjectById(Form.class, formId);
							if (form != null) {
								logInfoOutput("Form Name=" + form.getName());
								String formType = (form.getType() == null) ? "" : form.getType().name();
								logInfoOutput("Form Type=" + formType);
								String isFormHidden = String.valueOf(form.isHidden());
								logInfoOutput("Form Hidden=" + isFormHidden);

								String description = (null == form.getDescription()) ? ""
										: form.getDescription().replaceAll("[\t\n\r]", "");

								logInfoOutput("Form Description=" + description);

								logInfoOutput("Form Created=" + form.getCreated());
								logInfoOutput("Form Modified=" + form.getModified());

								String formOwnwer = (form.getOwner() == null) ? "" : form.getOwner().getName();
								logInfoOutput("Form Owner=" + formOwnwer);

								Attributes<String, Object> formArgs = form.getAttributes();
								StringBuffer formAttributes = new StringBuffer();
								if (null != formArgs) {
									List<String> formAttrKeys = formArgs.getKeys();
									if (null != formAttrKeys) {
										for (String formAttrKey : formAttrKeys) {
											if (null != formArgs.get(formAttrKey)
													&& formArgs.get(formAttrKey) instanceof sailpoint.object.Script)
												formAttributes.append(formAttrKey + "="
														+ ((Script) formArgs.get(formAttrKey)).getSource() + " ");
											else
												formAttributes
														.append(formAttrKey + "=" + formArgs.get(formAttrKey) + " ");
										}
									}
								}
								String formTargetUser = form.getTargetUser();
								logInfoOutput("Form TargetUser=" + formTargetUser);
								// write out to the files
								if (writeToConsolidatedCSVFile)
									fileOut.print(form.getName() + " | " + description + " | " + formType + " | "
											+ isFormHidden + " | " + form.getCreated() + " | " + form.getModified()
											+ " | " + formOwnwer + " | " + formAttributes.toString() + " | "
											+ formTargetUser + "\n");
								Row row = sheetForms.createRow(rowNum++);
								int colNum = 0;
								Cell cell = row.createCell(colNum++);
								cell.setCellValue((String) form.getName());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) description);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) formType);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) isFormHidden);
								cell = row.createCell(colNum++);

								if (null != form.getCreated())
									cell.setCellValue((String) sdf.format(form.getCreated()));
								else
									cell.setCellValue("");
								cell = row.createCell(colNum++);
								if (null != form.getModified())
									cell.setCellValue((String) sdf.format(form.getModified()));
								else
									cell.setCellValue("");

								cell = row.createCell(colNum++);

								cell.setCellValue((String) formOwnwer);
								cell = row.createCell(colNum++);
								cell.setCellValue((String) formAttributes.toString());
								cell = row.createCell(colNum++);
								cell.setCellValue((String) formTargetUser);
								cell = row.createCell(colNum++);
								context.decache(form);
							}
						}
					}
				}
			} // End of Form Definitions

			// ObjectConfig Definitions
				logInfoOutput("ObjectConfig Info");

				// Passing null as query options to fetch all
				Iterator<Object[]> objectConfigIdsIter = context.search(ObjectConfig.class, null, "id");
				if (objectConfigIdsIter != null) {
					if (writeToConsolidatedCSVFile)
						fileOut.print(
								"ObjectConfig Name | ObjectConfig Description | ObjectConfig Created | ObjectConfig Modified | ObjectConfig Owner | ObjectConfig Custom Attributes | ObjectConfig Editable Attributes | ObjectConfig Extended Attributes | ObjectConfig Multi Attributes | ObjectConfig Object Attributes | ObjectConfig Searchable Attributes | ObjectConfig Standard Attributes \n");
					XSSFSheet sheetObjectConfigs = workbook.createSheet("ObjectConfig");
					Object[][] datatypes = { { "ObjectConfig Name", "ObjectConfig Description", "ObjectConfig Created",
							"ObjectConfig Modified", "ObjectConfig Owner", "ObjectConfig Attribute Name", "ObjectConfig Custom Attributes",
							"ObjectConfig Editable Attributes", "ObjectConfig Extended Attributes",
							"ObjectConfig Multi Attributes", "ObjectConfig Object Attributes",
							"ObjectConfig Searchable Attributes", "ObjectConfig Standard Attributes"
							 } };

					int rowNum = 0;
					logInfoOutput("Creating Excel sheet ObjectConfig");

					for (Object[] datatype : datatypes) {
						Row row = sheetObjectConfigs.createRow(rowNum++);
						int colNum = 0;
						for (Object field : datatype) {
							Cell cell = row.createCell(colNum++);
							if (field instanceof String) {
								cell.setCellValue((String) field);
							} else if (field instanceof Integer) {
								cell.setCellValue((Integer) field);
							}
						}
					}

					while (objectConfigIdsIter.hasNext()) {
						Object[] objectConfigIds = (Object[]) objectConfigIdsIter.next();
						if (objectConfigIds != null) {
							String objectConfigId = (String) objectConfigIds[0];
							if (objectConfigId != null) {
								ObjectConfig objectConfig = context.getObjectById(ObjectConfig.class, objectConfigId);
								if (objectConfig != null) {
									logInfoOutput("ObjectConfig Name=" + objectConfig.getName());

									String description = (null == objectConfig.getDescription()) ? ""
											: objectConfig.getDescription().replaceAll("[\t\n\r]", "");

									logInfoOutput("ObjectConfig Description=" + description);

									logInfoOutput("ObjectConfig Created=" + objectConfig.getCreated());
									logInfoOutput("ObjectConfig Modified=" + objectConfig.getModified());

									// write out to the files

									// TODO Add additional rows
									if (writeToConsolidatedCSVFile)
										fileOut.print(objectConfig.getName() + " | " + description + " | "
												+ objectConfig.getCreated() + " | " + objectConfig.getModified() + "\n");
									Row row = sheetObjectConfigs.createRow(rowNum++);
									int colNum = 0;

									// Set Style
									CellStyle fontStyle = workbook.createCellStyle();
									XSSFFont font = workbook.createFont();
									font.setBold(true);
									font.setItalic(false);

									fontStyle.setFillBackgroundColor(IndexedColors.DARK_BLUE.getIndex());
									fontStyle.setFont(font);
									fontStyle.setWrapText(true);

									Cell cell = row.createCell(colNum++);
									cell.setCellValue((String) objectConfig.getName());
									cell = row.createCell(colNum++);
									cell.setCellValue((String) description);
									cell = row.createCell(colNum++);
									if (null != objectConfig.getCreated())
										cell.setCellValue((String) sdf.format(objectConfig.getCreated()));
									else
										cell.setCellValue("");
									cell = row.createCell(colNum++);
									if (null != objectConfig.getModified())
										cell.setCellValue((String) sdf.format(objectConfig.getModified()));
									else
										cell.setCellValue("");

									cell = row.createCell(colNum++);
									String objectConfigOwner = (null == objectConfig.getOwner()) ? ""
											: objectConfig.getOwner().getName();
									cell.setCellValue((String) objectConfigOwner);
									
									cell = row.createCell(colNum++);
									cell.setCellStyle(fontStyle);
									cell.setCellValue("Attribute Properties");
																	
									//Prepare a consolidated map between Attribute type and list of attributes of this type
									Map attrTypesMap = new HashMap();
											
									List<ObjectAttribute> customAttrs = objectConfig.getCustomAttributes();
									
									updateAttributesTypeMap(customAttrs, attrTypesMap , "Custom Attributes");
									
									List<ObjectAttribute> editableAttrs = objectConfig.getEditableAttributes();
									updateAttributesTypeMap(editableAttrs, attrTypesMap , "Editable Attributes");
									
									List<ObjectAttribute> extendedAttrs = objectConfig.getExtendedAttributeList();
									updateAttributesTypeMap(extendedAttrs, attrTypesMap , "Extended Attributes");
									
									List<ObjectAttribute> multiAttrs = objectConfig.getMultiAttributeList();
									updateAttributesTypeMap(multiAttrs, attrTypesMap , "Multi Attributes");
									
									List<ObjectAttribute> objectAttrs = objectConfig.getObjectAttributes();
									updateAttributesTypeMap(objectAttrs, attrTypesMap , "Object Attributes");
									
									List<ObjectAttribute> searchableAttrs = objectConfig.getSearchableAttributes();
									updateAttributesTypeMap(searchableAttrs, attrTypesMap , "Searchable Attributes");
									
									List<ObjectAttribute> standardAttrs = objectConfig.getStandardAttributes();
									updateAttributesTypeMap(standardAttrs, attrTypesMap , "Standard Attributes");
									
									// Note: This is hard coded based on number of previous columns
									int colCountInput = 6;
									rowNum = insertObjectConfigAttrs(sheetObjectConfigs, rowNum, colCountInput, attrTypesMap,
											fontStyle);
									context.decache(objectConfig);
								}
							}
						}
					}
				} // End of ObjectConfig Definitions

			// Workgroup Definitions (including a member list)

			logInfoOutput("Workgroups Info");

			qo = new QueryOptions();
			// Get the workgroups first
			qo.addFilter(Filter.eq("workgroup", true));
			// Passing null as query options to fetch all
			Iterator<Object[]> wkGrpsIdsIter = context.search(Identity.class, qo, "id");
			Map<String, Object> workGps = new HashMap<String, Object>();

			String workGpName = "";
			StringBuffer workGpPreferencesStr = new StringBuffer();
			if (wkGrpsIdsIter != null) {
				while (wkGrpsIdsIter.hasNext()) {
					Object[] wkGrpsIdsAttr = (Object[]) wkGrpsIdsIter.next();
					if (wkGrpsIdsAttr != null) {
						String wkGrpsId = (String) wkGrpsIdsAttr[0];
						if (wkGrpsId != null) {
							Identity workGroup = context.getObjectById(Identity.class, wkGrpsId);
							if (workGroup != null) {
								workGpName = workGroup.getName();
								logInfoOutput("workGpName=" + workGpName);
								Map<String, Object> workGpPreferences = workGroup.getPreferences();
								logInfoOutput("workGpPreferences=" + workGpPreferences);
								if (null != workGpPreferences
										&& null != workGpPreferences.get("workgroupNotificationOption")) {
									workGpPreferencesStr.append("workgroupNotificationOption" + "="
											+ workGpPreferences.get("workgroupNotificationOption") + "  ");
								}
								logInfoOutput("workGpPreferencesStr=" + workGpPreferencesStr.toString());
								List<Object> workGpsPrefsAndMembersMaps = new ArrayList<Object>();
								Map<String, Object> workGroupPreferencesMap = new HashMap<String, Object>();

								workGpsPrefsAndMembersMaps.add(0, workGroupPreferencesMap);

								Map<String, Object> workGpAttrs = new HashMap<String, Object>();
								if (null != workGroup.getCreated())
									workGpAttrs.put("created", sdf.format(workGroup.getCreated()));
								else
									workGpAttrs.put("created", "");

								if (null != workGroup.getModified())
									workGpAttrs.put("modified", sdf.format(workGroup.getModified()));
								else
									workGpAttrs.put("modified", "");

								String description = (null == workGroup.getDescription()) ? ""
										: workGroup.getDescription().replaceAll("[\t\n\r]", "");
								workGpAttrs.put("description", description);
								logInfoOutput("workGpAttrs=" + workGpAttrs);
								Map<String, Object> workGroupAttributesMap = new HashMap<String, Object>();
								workGroupAttributesMap.put("WorkGroupAttributes", workGpAttrs);
								workGpsPrefsAndMembersMaps.add(1, workGroupAttributesMap);
								logInfoOutput("workGpsPrefsAndMembersMaps=" + workGpsPrefsAndMembersMaps);
								workGps.put(workGpName, workGpsPrefsAndMembersMaps);
								logInfoOutput("workGps=" + workGps);
							}
						}
					}
				}
			}

			// Iterate through all identities to get workgroup memberships
			// Passing null as query options to fetch all
			// TODO Test single identity
			qo = new QueryOptions();
			// Get the workgroups first
			Iterator<Object[]> idyIdsIter = context.search(Identity.class, null, "id");
			Map<String, Object> identityWorkGroupsMap = new HashMap<String, Object>();

			if (idyIdsIter != null) {
				while (idyIdsIter.hasNext()) {
					Object[] idyIdAttr = (Object[]) idyIdsIter.next();
					if (idyIdAttr != null) {
						String idyId = (String) idyIdAttr[0];
						if (idyId != null) {
							Identity identity = context.getObjectById(Identity.class, idyId);
							List<Identity> identityWrkGrps = identity.getWorkgroups();
							logInfoOutput("Checking workgroup members for identity=" + identity.getName());
							logInfoOutput(
									"Checking workgroup members for identity: identityWrkGrps=" + identityWrkGrps);
							if (null != identityWrkGrps) {
								for (Identity identityWrkGrp : identityWrkGrps) {
									logInfoOutput("identityWrkGrp.getName()=" + identityWrkGrp.getName());
									if (null == identityWorkGroupsMap.get(identityWrkGrp.getName())) {
										List<String> identities = new ArrayList<String>();
										identities.add(identity.getName());
										identityWorkGroupsMap.put(identityWrkGrp.getName(), identities);
										logInfoOutput("identityWorkGroupsMap=" + identityWorkGroupsMap);
									} else {
										((List<Object>) identityWorkGroupsMap.get(identityWrkGrp.getName()))
												.add(identity.getName());
										logInfoOutput("identityWorkGroupsMap=" + identityWorkGroupsMap);
									}

									logInfoOutput("identityWorkGroupsMap=" + identityWorkGroupsMap);
								}
							}
							context.decache(identity);
						}
					}
				}
			}
			Set<String> workGroupNames = workGps.keySet();
			logInfoOutput("workGroupNames=" + workGroupNames);
			if (null != workGroupNames) {
				for (String workGroupNameKey : workGroupNames) {
					logInfoOutput("workGroupNameKey=" + workGroupNameKey);
					logInfoOutput("workGps.get(workGroupNameKey)=" + workGps.get(workGroupNameKey));
					logInfoOutput("identityWorkGroupsMap.get(workGroupNameKey)="
							+ identityWorkGroupsMap.get(workGroupNameKey));
					Map<String, Object> workGroupMembersMap = new HashMap<String, Object>();
					workGroupMembersMap.put("WorkGroupMembers", identityWorkGroupsMap.get(workGroupNameKey));

					((List<Map<String, Object>>) workGps.get(workGroupNameKey)).add(2, workGroupMembersMap);
				}
			}
			logInfoOutput("Resulting workGps info=" + workGps);

			// write out to the files
			if (writeToConsolidatedCSVFile)
				fileOut.print(
						"Workgroup Name | Workgroup Description |Workgroup Preferences | Workgroup Members | Workgroup Created | Workgroup Modified \n");
			XSSFSheet sheetWorkGroups = workbook.createSheet("Workgroup");
			Object[][] datatypes = { { "Workgroup Name", "Workgroup Description", "Workgroup Preferences",
					"Workgroup Members", "Workgroup Created", "Workgroup Modified" } };

			int rowNum = 0;
			logInfoOutput("Creating sheet Workgroup");

			for (Object[] datatype : datatypes) {
				Row row = sheetWorkGroups.createRow(rowNum++);
				int colNum = 0;
				for (Object field : datatype) {
					Cell cell = row.createCell(colNum++);
					if (field instanceof String) {
						cell.setCellValue((String) field);
					} else if (field instanceof Integer) {
						cell.setCellValue((Integer) field);
					}
				}
			}
			workGroupNames = workGps.keySet();

			if (null != workGroupNames) {
				for (String workGroupNameKey : workGroupNames) {
					List<Map<String, Object>> workGpAttrsMaps = (List<Map<String, Object>>) workGps
							.get(workGroupNameKey);

					Map<String, Object> workGroupMembersMap = new HashMap<String, Object>();
					Map<String, Object> workGroupPreferencesMap = new HashMap<String, Object>();
					Map<String, Object> workGroupAttributesMap = new HashMap<String, Object>();
					if (null != workGpAttrsMaps) {
						workGroupPreferencesMap = (Map<String, Object>) workGpAttrsMaps.get(0);
						workGroupAttributesMap = (Map<String, Object>) workGpAttrsMaps.get(1);
						workGroupMembersMap = (Map<String, Object>) workGpAttrsMaps.get(2);
					}

					String description = (String) ((Map<String, Object>) workGroupAttributesMap
							.get("WorkGroupAttributes")).get("description");

					String created = (String) ((Map<String, Object>) workGroupAttributesMap.get("WorkGroupAttributes"))
							.get("created");

					String modified = (String) ((Map<String, Object>) workGroupAttributesMap.get("WorkGroupAttributes"))
							.get("modified");

					String workGroupMembers = Util
							.listToCsv(((List<Map<String, Object>>) workGroupMembersMap.get("WorkGroupMembers")));

					if (writeToConsolidatedCSVFile)
						fileOut.print(workGroupNameKey + " | " + description + " | "
								+ workGroupPreferencesMap.get("WorkGroupPreferences") + " | " + workGroupMembers + " | "
								+ created + " | " + modified + "\n");
					Row row = sheetWorkGroups.createRow(rowNum++);
					int colNum = 0;
					Cell cell = row.createCell(colNum++);
					cell.setCellValue((String) workGroupNameKey);
					cell = row.createCell(colNum++);
					cell.setCellValue((String) description);
					cell = row.createCell(colNum++);
					cell.setCellValue((String) workGroupPreferencesMap.get("WorkGroupPreferences"));
					cell = row.createCell(colNum++);
					cell.setCellValue((String) workGroupMembers);

					cell = row.createCell(colNum++);
					cell.setCellValue((String) created);
					cell = row.createCell(colNum++);
					cell.setCellValue((String) modified);
					cell = row.createCell(colNum++);

				}
			}
			// End of WorkGroup Definitions

			// Save Excel workbook
			FileOutputStream outputStream = new FileOutputStream(_outputPath);
			workbook.write(outputStream);

		} catch (FileNotFoundException e) {
			throw new GeneralException("Could not find path to " + _outputPath);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (null != fileOut) {
				fileOut.flush();
				fileOut.close();
			}
			if (null != workbook)
				workbook.close();
		}
		logInfoOutput("End IdentityIQ Summarizer");

	}

	// ***********************************************************************
	// * Helper methods
	// ***********************************************************************
	public void logInfoOutput(String msg) {
		logger.debug(msg);
	}

	public void logErrorOutput(String msg) {
		logger.error(msg);
	}

	public void logWarnOutput(String msg) {
		logger.warn(msg);
	}

	public static int countLines(String str) {
		if (str == null || str.isEmpty()) {
			return 0;
		}
		int lines = 1;
		int pos = 0;
		while ((pos = str.indexOf("\n", pos) + 1) != 0) {
			lines++;
		}
		return lines;
	}

	public int insertObjectConfigAttrs(XSSFSheet sheetObjectConfigs, int rowCountInput, int colCountInput,
			 Map attrTypesMap, CellStyle fontStyle) {
		logInfoOutput("Entering into insertObjectConfigAttrs");
		logInfoOutput("Input attrTypesMap=[" + attrTypesMap+"]");
		int rowNum = rowCountInput;
		if (null != attrTypesMap) {
			Set <String> attrKeys = attrTypesMap.keySet();			
			for (String attrKey : attrKeys) {
				
				ObjectAttribute attr = (ObjectAttribute)((Map)attrTypesMap.get(attrKey)).get("object");
				// Note: This is hard coded based on number of previous columns
				int colNum = colCountInput-1;
				
				Row row = sheetObjectConfigs.createRow(rowNum++);
				logInfoOutput("Attr Name=" + attr.getName());
				Cell cell = row.createCell(colNum);

				cell.setCellStyle(fontStyle);
				cell.setCellValue((String) " Attr Name=" + attr.getName());
				
				int colNumForTypes = colCountInput;
				Cell cellForType = row.createCell(colNumForTypes++);
				cellForType.setCellStyle(fontStyle);
				
				List <String> attrTypes = (List)((Map)attrTypesMap.get(attrKey)).get("types");
				
				if(null != attrTypesMap && attrTypes.contains("Custom Attributes") )
				{	
					cellForType.setCellValue((String)"Yes");
				}
				else
					cellForType.setCellValue((String)"No");
				
				cellForType = row.createCell(colNumForTypes++);
				cellForType.setCellStyle(fontStyle);
				
				if(null != attrTypesMap &&  attrTypes.contains("Editable Attributes"))
				{					
					cellForType.setCellValue((String)"Yes");
				}
				else
					cellForType.setCellValue((String)"No");
				
				cellForType = row.createCell(colNumForTypes++);
				cellForType.setCellStyle(fontStyle);
				
				if(null != attrTypesMap &&  attrTypes.contains("Extended Attributes") )
				{					
					cellForType.setCellValue((String)"Yes");
				}
				else
					cellForType.setCellValue((String)"No");
				cellForType = row.createCell(colNumForTypes++);
				cellForType.setCellStyle(fontStyle);
				
				if(null != attrTypesMap &&  attrTypes.contains("Multi Attributes") )
				{					
					cellForType.setCellValue((String)"Yes");
				}
				else
					cellForType.setCellValue((String)"No");
				cellForType = row.createCell(colNumForTypes++);
				cellForType.setCellStyle(fontStyle);
				
				if(null != attrTypesMap &&  attrTypes.contains("Object Attributes") )
				{	
					cellForType.setCellValue((String)"Yes");
				}
				else
					cellForType.setCellValue((String)"No");
				cellForType = row.createCell(colNumForTypes++);
				cellForType.setCellStyle(fontStyle);
				
				if(null != attrTypesMap &&  attrTypes.contains("Searchable Attributes") )
				{	
					cellForType.setCellValue((String)"Yes");
				}
				else
					cellForType.setCellValue((String)"No");
				cellForType = row.createCell(colNumForTypes++);
				cellForType.setCellStyle(fontStyle);
				
				if(null != attrTypesMap &&  attrTypes.contains("Standard Attributes") )
				{	
					cellForType.setCellValue((String)"Yes");
				}
				else
					cellForType.setCellValue((String)"No");
				
				row = sheetObjectConfigs.createRow(rowNum++);
				logInfoOutput("	Type=" + attr.getType());
				cell = row.createCell(colNum);
				cell.setCellValue((String) "	Type=" + attr.getType());

				row = sheetObjectConfigs.createRow(rowNum++);
				logInfoOutput("	Category Name=" + attr.getCategoryName());
				cell = row.createCell(colNum);
				cell.setCellValue((String) "	Category Name=" + attr.getCategoryName());

				row = sheetObjectConfigs.createRow(rowNum++);
				logInfoOutput(" Default Value=" + attr.getDefaultValueAsString());
				cell = row.createCell(colNum);
				cell.setCellValue((String) "	Default Value=" + attr.getDefaultValueAsString());

				row = sheetObjectConfigs.createRow(rowNum++);
				logInfoOutput("	Edit Mode=" + attr.getEditModeString());
				cell = row.createCell(colNum);
				cell.setCellValue((String) "	Edit Mode=" + attr.getEditModeString());

				row = sheetObjectConfigs.createRow(rowNum++);
				String allowedOperations = (null == attr.getAllowedOperations()) ? ""
						: Util.listToCsv(attr.getAllowedOperations());
				logInfoOutput("	Allowed Operations=" + allowedOperations);
				cell = row.createCell(colNum);
				cell.setCellValue((String) "	Allowed Operations=" + allowedOperations);

				row = sheetObjectConfigs.createRow(rowNum++);
				String listenerRule = (null == attr.getListenerRule()) ? "" : attr.getListenerRule().getName();
				logInfoOutput("	Listener Rule Name=" + listenerRule);
				cell = row.createCell(colNum);
				cell.setCellValue((String) "	Listener Rule Name=" + listenerRule);

				row = sheetObjectConfigs.createRow(rowNum++);
				String listenerWorkflow = (null == attr.getListenerWorkflow()) ? ""
						: attr.getListenerWorkflow().getName();
				logInfoOutput("	Listener Workflow Name=" + listenerWorkflow);
				cell = row.createCell(colNum);
				cell.setCellValue((String) "	Listener Rule Name=" + listenerWorkflow);

				row = sheetObjectConfigs.createRow(rowNum++);
				String propertyType = (null == attr.getPropertyType()) ? "" : attr.getPropertyType().name();
				logInfoOutput("	Property Type=" + propertyType);
				cell = row.createCell(colNum);
				cell.setCellValue((String) "	Property Type=" + propertyType);

				row = sheetObjectConfigs.createRow(rowNum++);
				String rule = (null == attr.getRule()) ? "" : attr.getRule().getName();
				logInfoOutput("	Rule Name=" + rule);
				cell = row.createCell(colNum);
				cell.setCellValue((String) "	Rule Name=" + rule);

				List<AttributeSource> attributeSources = attr.getSources();

				if (null != attributeSources) {
					StringBuffer attrSourcesInfo = new StringBuffer();
					for (AttributeSource attributeSource : attributeSources) {
						attrSourcesInfo.append("	Attribute Source Name=" + attributeSource.getName());
						String appName = (null == attributeSource.getApplication()) ? ""
								: attributeSource.getApplication().getName();
						String ruleName = (null == attributeSource.getRule()) ? ""
								: attributeSource.getRule().getName();
						attrSourcesInfo.append("	Application Name=" + appName);
						if (!ruleName.isEmpty())
							attrSourcesInfo.append(" Rule Name=" + ruleName);

						attrSourcesInfo.append("\r\n");
					}

					row = sheetObjectConfigs.createRow(rowNum++);
					logInfoOutput("	Attribute Sources Info=" + attrSourcesInfo.toString());
					cell = row.createCell(colNum);
					cell.setCellValue((String) "	Attribute Sources Info=" + attrSourcesInfo.toString());
				}

				List<AttributeTarget> attributeTargets = attr.getTargets();

				if (null != attributeTargets) {
					StringBuffer attrTargetsInfo = new StringBuffer();
					for (AttributeTarget attributeTarget : attributeTargets) {
						attrTargetsInfo.append("	Attribute Target Name=" + attributeTarget.getName());
						String appName = (null == attributeTarget.getApplication()) ? ""
								: attributeTarget.getApplication().getName();
						String ruleName = (null == attributeTarget.getRule()) ? ""
								: attributeTarget.getRule().getName();
						attrTargetsInfo.append("	Application Name=" + appName);
						if (!ruleName.isEmpty())
							attrTargetsInfo.append(" Rule Name=" + ruleName);

						attrTargetsInfo.append("\r\n");
					}

					row = sheetObjectConfigs.createRow(rowNum++);
					logInfoOutput("	Attribute Targets Info=" + attrTargetsInfo.toString());
					cell = row.createCell(colNum);
					cell.setCellValue((String) "	Attribute Targets Info=" + attrTargetsInfo.toString());
				}

			}
		}
		logInfoOutput("Exiting into insertObjectConfigAttrs");
		return rowNum;

	}
	public void updateAttributesTypeMap(List<ObjectAttribute> attrs, Map attrTypesMap , String type) {
			logInfoOutput("Entering into updateAttributesTypeMap");
			logInfoOutput("Input Attrs=" + attrs);
			
			if(null != attrs && !attrs.isEmpty())
			{													
				for(ObjectAttribute attr: attrs)
				{
					if(null == attrTypesMap.get(attr.getName()))
					{
						List types = new ArrayList();
						types.add(type);
						
						Map attrObjAndTypesMap = new HashMap();
						attrObjAndTypesMap.put("types", types);												
						attrObjAndTypesMap.put("object", attr);						
						attrTypesMap.put(attr.getName(), attrObjAndTypesMap);						
						
					}
					else
					{
						((List)((Map)attrTypesMap.get(attr.getName())).get("types")).add(type);
					}					
				}				
			}
			logInfoOutput("Exiting updateAttributesTypeMap. attrTypesMap=["+attrTypesMap+"]");
			
			return;
	}

	public boolean terminate() {

		terminate = true;

		return terminate;
	}

}
