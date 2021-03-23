package sailpoint.services.standard.customizationplugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;
import sailpoint.api.SailPointContext;
import sailpoint.object.Attributes;
import sailpoint.object.LiveReport;
import sailpoint.object.QueryOptions;
import sailpoint.object.Sort;
import sailpoint.reporting.datasource.JavaDataSource;
import sailpoint.server.Environment;
import sailpoint.task.Monitor;
import sailpoint.tools.GeneralException;

public class GenericPluginReport implements JavaDataSource {

	JavaDataSource pluginReportObj = null;

	@Override
	public String getBaseHql() {

		return pluginReportObj.getBaseHql();
	}

	@Override
	public QueryOptions getBaseQueryOptions() {

		return pluginReportObj.getBaseQueryOptions();
	}

	@Override
	public Object getFieldValue(String arg0) throws GeneralException {

		return pluginReportObj.getFieldValue(arg0);
	}

	@Override
	public int getSizeEstimate() throws GeneralException {
		return pluginReportObj.getSizeEstimate();
	}

	@Override
	public void close() {
		pluginReportObj.close();

	}

	@Override
	public void setMonitor(Monitor arg0) {
		pluginReportObj.setMonitor(arg0);

	}

	@Override
	public Object getFieldValue(JRField arg0) throws JRException {

		return pluginReportObj.getFieldValue(arg0);
	}

	@Override
	public boolean next() throws JRException {

		return pluginReportObj.next();
	}

	@Override
	public void initialize(SailPointContext arg0, LiveReport arg1, Attributes<String, Object> arg2, String arg3, List<Sort> arg4) throws GeneralException {

		if (pluginReportObj == null) {
			String pluginName = arg2.getString("pluginName");
			String pluginClassName = arg2.getString("pluginClassName");
			try {
				ClassLoader clsLoader = Environment.getEnvironment().getPluginsCache().getClassLoader(pluginName);
				Class clObj = clsLoader.loadClass(pluginClassName);
				

				Constructor constr = clObj.getConstructor();

				pluginReportObj = (JavaDataSource) constr.newInstance();

				// pluginReportObj = new GenericPluginReport();
			} catch (Exception e) {

				throw new GeneralException(e);
			}

		}

		pluginReportObj.initialize(arg0, arg1, arg2, arg3, arg4);

	}

	@Override
	public void setLimit(int arg0, int arg1) {
		pluginReportObj.setLimit(arg0, arg1);

	}

}
