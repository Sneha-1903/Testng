package org.testng.eclipse.launch;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.launching.AbstractJavaLaunchConfigurationDelegate;
import org.eclipse.jdt.launching.ExecutionArguments;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMRunner;
import org.eclipse.jdt.launching.SocketUtil;
import org.eclipse.jdt.launching.VMRunnerConfiguration;
import org.testng.CommandLineArgs;
import org.testng.ITestNGListener;
import org.testng.eclipse.TestNGPlugin;
import org.testng.eclipse.launch.TestNGLaunchConfigurationConstants.LaunchType;
import org.testng.eclipse.ui.util.ConfigurationHelper;
import org.testng.eclipse.util.LaunchUtil;
import org.testng.eclipse.util.ListenerContributorUtil;
import org.testng.eclipse.util.PreferenceStoreUtil;
import org.testng.eclipse.util.ResourceUtil;
import org.testng.eclipse.util.StringUtils;
import org.testng.remote.RemoteArgs;
import org.testng.remote.RemoteTestNG;
import org.testng.xml.LaunchSuite;

public class TestNGLaunchConfigurationDelegate extends AbstractJavaLaunchConfigurationDelegate {

  /**
   * Launch RemoteTestNG (except if we're in debug mode).
   */
  public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch,
      IProgressMonitor monitor) throws CoreException {
    IJavaProject javaProject = getJavaProject(configuration);
    if ((javaProject == null) || !javaProject.exists()) {
      abort(ResourceUtil.getString("TestNGLaunchConfigurationDelegate.error.invalidproject"), //$NON-NLS-1$
          null, IJavaLaunchConfigurationConstants.ERR_NOT_A_JAVA_PROJECT);
    }

    IVMInstall install = getVMInstall(configuration);
    IVMRunner runner = install.getVMRunner(mode);
    if (runner == null) {
      abort(ResourceUtil.getFormattedString("TestNGLaunchConfigurationDelegate.error.novmrunner", //$NON-NLS-1$
          new String[] { install.getId() }), null,
          IJavaLaunchConfigurationConstants.ERR_VM_RUNNER_DOES_NOT_EXIST);
    }

    int port = SocketUtil.findFreePort();
    VMRunnerConfiguration runConfig = launchTypes(configuration, launch, javaProject, port, mode);
    setDefaultSourceLocator(launch, configuration);

    launch.setAttribute(TestNGLaunchConfigurationConstants.PORT, Integer.toString(port));
    launch.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, javaProject
        .getElementName());
    launch.setAttribute(TestNGLaunchConfigurationConstants.TESTNG_RUN_NAME_ATTR,
        getRunNameAttr(configuration));

    StringBuilder sb = new StringBuilder();
    for (String arg : runConfig.getProgramArguments()) {
      sb.append(arg).append(" ");
    }
    TestNGPlugin.log("[TestNGLaunchConfigurationDelegate] " + debugConfig(runConfig));
    runner.run(runConfig, launch, monitor);
  }

  private static String join(String[] strings) {
    return join(strings, " ");
  }

  private static String join(String[] strings, String sep) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < strings.length; i++) {
      if (i > 0) sb.append(sep);
      sb.append(strings[i]);
    }
    return sb.toString();
  }

  private String debugConfig(VMRunnerConfiguration config) {
    StringBuilder sb = new StringBuilder("Launching:");
    sb.append("\n  Classpath: " + join(config.getClassPath()));
    sb.append("\n  VMArgs:    " + join(config.getVMArguments()));
    sb.append("\n  Class:     " + config.getClassToLaunch());
    sb.append("\n  Args:      " + join(config.getProgramArguments()));
    sb.append("\n");

    sb.append("java "
        + join(config.getVMArguments())
        + " -classpath " + join(config.getClassPath(), ":")
        + " " + config.getClassToLaunch()
        + " " + join(config.getProgramArguments()));

    return sb.toString();
  }

  private static void p(String s) {
    if (TestNGPlugin.isVerbose()) {
      System.out.println("[TestNGLaunchConfigurationDelegate] " + s);
    }
  }

  @SuppressWarnings("unchecked")
  protected VMRunnerConfiguration launchTypes(final ILaunchConfiguration configuration,
      ILaunch launch, final IJavaProject jproject, final int port, final String mode)
      throws CoreException {

    File workingDir = verifyWorkingDirectory(configuration);
    String workingDirName = null;
    if (workingDir != null) {
      workingDirName = workingDir.getAbsolutePath();
    }

    // Program & VM args
    StringBuilder vmArgs = new StringBuilder(ConfigurationHelper.getJvmArgs(configuration))
        // getVMArguments(configuration))
        .append(" ")
        .append(TestNGLaunchConfigurationConstants.VM_ENABLEASSERTION_OPTION); // $NON-NLS-1$
    addDebugProperties(vmArgs);
    ExecutionArguments execArgs = new ExecutionArguments(vmArgs.toString(), ""); //$NON-NLS-1$
    String[] envp = DebugPlugin.getDefault().getLaunchManager().getEnvironment(configuration);

    VMRunnerConfiguration runConfig = createVMRunner(configuration, launch, jproject, port, mode);
    runConfig.setVMArguments(execArgs.getVMArgumentsArray());
    runConfig.setWorkingDirectory(workingDirName);
    runConfig.setEnvironment(envp);

    Map vmAttributesMap = getVMSpecificAttributesMap(configuration);
    runConfig.setVMSpecificAttributesMap(vmAttributesMap);

    String[] bootpath = getBootpath(configuration);
    runConfig.setBootClassPath(bootpath);

    return runConfig;
  }

  /**
   * Pass the system properties we were called with to the RemoteTestNG process.
   */
  private void addDebugProperties(StringBuilder vmArgs) {
    String[] debugProperties = new String[] {
        RemoteTestNG.PROPERTY_DEBUG,
        RemoteTestNG.PROPERTY_VERBOSE
    };
    for (String p : debugProperties) {
      if (System.getProperty(p) != null) {
        vmArgs.append(" -D").append(p);
      }
    }
  }

  @Override
  public String getMainTypeName(ILaunchConfiguration configuration)
      throws CoreException {
    return TestNGPlugin.isDebug() ? EmptyRemoteTestNG.class.getName()
        : RemoteTestNG.class.getName();
  }

  /**
   * This class creates the parameters to launch RemoteTestNG with the
   * correct parameters.
   *
   * Add a VMRunner with a class path that includes org.eclipse.jdt.junit
   * plugin. In addition it adds the port for the RemoteTestRunner as an
   * argument.
   */
  protected VMRunnerConfiguration createVMRunner(final ILaunchConfiguration configuration,
      ILaunch launch, final IJavaProject jproject, final int port, final String runMode)
      throws CoreException {

    String[] classPath = getClasspath(configuration);
    String progArgs = getProgramArguments(configuration);
    VMRunnerConfiguration vmConfig =
        new VMRunnerConfiguration(getMainTypeName(configuration), classPath);

    // insert the program arguments
    Vector<String> argv = new Vector<String>(10);
    ExecutionArguments execArgs = new ExecutionArguments("", progArgs); //$NON-NLS-1$
    String[] pa = execArgs.getProgramArgumentsArray();
    for (int i = 0; i < pa.length; i++) {
      argv.add(pa[i]);
    }

    // Use -serPort (serialized protocol) or -port (string protocol) based on
    // a system property
    if (LaunchUtil.useStringProtocol(configuration)) {
      p("Using the string protocol");
      argv.add(CommandLineArgs.PORT);
    } else {
      p("Using the serialized protocol");
      argv.add(RemoteArgs.PORT);
    }
    argv.add(Integer.toString(port));

    IProject project = jproject.getProject();

//    if (!isJDK15(javaVersion)) {
//      List<File> sourceDirs = JDTUtil.getSourceDirFileList(jproject);
//      if (null != sourceDirs) {
//        argv.add(TestNGCommandLineArgs.SRC_COMMAND_OPT);
//        argv.add(Utils.toSinglePath(sourceDirs, ";")); //$NON-NLS-1$
//      }
//    }

    PreferenceStoreUtil storage = TestNGPlugin.getPluginPreferenceStore();
    argv.add(CommandLineArgs.OUTPUT_DIRECTORY);
    argv.add(storage.getOutputAbsolutePath(jproject).toOSString());

//    String reporters = storage.getReporters(project.getName(), false);
//    if (null != reporters && !"".equals(reporters)) {
//      argv.add(TestNGCommandLineArgs.LISTENER_COMMAND_OPT);
//      argv.add(reporters.replace(' ', ';'));
//    }

    List<ITestNGListener> contributors = ListenerContributorUtil.findReporterContributors();
    contributors.addAll(ListenerContributorUtil.findTestContributors());
    StringBuffer reportersContributors = new StringBuffer();
    boolean isFirst = true;
    for (ITestNGListener contributor : contributors) {
      if (isFirst) {
        reportersContributors.append(contributor.getClass().getName());
      } else {
        reportersContributors.append(";" + contributor.getClass().getName());
      }
      isFirst = false;
    }
    if (!reportersContributors.toString().trim().equals("")) {
      if (!argv.contains(CommandLineArgs.LISTENER)) {
        argv.add(CommandLineArgs.LISTENER);
        argv.add(reportersContributors.toString().trim());
      } else {
        String listeners = argv.get(argv.size() - 1);
        listeners += (";" + reportersContributors.toString().trim());
        argv.set(argv.size() - 1, listeners);
      }
    }

    boolean disabledReporters = storage.hasDisabledListeners(project.getName(), false);
    if (disabledReporters) {
      argv.add(CommandLineArgs.USE_DEFAULT_LISTENERS);
      argv.add("false");
    }

    List<LaunchSuite> launchSuiteList =
        ConfigurationHelper.getLaunchSuites(jproject, configuration);
    List<String> suiteList = new ArrayList<String>();
    List<String> tempSuites = new ArrayList<String>();

    // Regular mode: generate a new random suite path
    File suiteDir = TestNGPlugin.isDebug() ? new File(RemoteTestNG.DEBUG_SUITE_DIRECTORY)
        : TestNGPlugin.getPluginPreferenceStore().getTemporaryDirectory();
    for (LaunchSuite launchSuite : launchSuiteList) {
      File suiteFile = launchSuite.save(suiteDir);

      suiteList.add(suiteFile.getAbsolutePath());

      if (launchSuite.isTemporary()) {
        suiteFile.deleteOnExit();
        tempSuites.add(suiteFile.getAbsolutePath());
      }
    }

    if (null != suiteList) {
      for (String suite : suiteList) {
        argv.add(suite);
      }

      launch.setAttribute(TestNGLaunchConfigurationConstants.TEMP_SUITE_LIST, 
          StringUtils.listToString(tempSuites));
    }

    vmConfig.setProgramArguments(argv.toArray(new String[argv.size()]));

    return vmConfig;
  }

  public String[] getClasspath(ILaunchConfiguration configuration)
      throws CoreException {
    String[] originalClasspath = super.getClasspath(configuration);

    String projectName = getJavaProjectName(configuration);
    boolean useProjectJar = TestNGPlugin.getPluginPreferenceStore().getUseProjectJar(projectName);
    if (useProjectJar) {
      return originalClasspath;
    }
    // Add our own lib/testng.jar unless this project is configured to use its own testng.jar
    else {
      String testngJarLocation = getTestNGLibraryVersion();
      String[] allClasspath = new String[originalClasspath.length + 1];
      try {
        // insert the bundle embedded testng.jar on the front of the classpath
        allClasspath[0] = FileLocator.toFileURL(TestNGPlugin.getDefault().getBundle().getEntry(testngJarLocation)).getFile();
      } catch (IOException ioe) {
        TestNGPlugin.log(ioe);
        abort("Cannot create runtime classpath", ioe, 1000); //$NON-NLS-1$
      }
      System.arraycopy(originalClasspath, 0, allClasspath, 1, originalClasspath.length);
      return allClasspath;
    }
  }

  private String getRunNameAttr(ILaunchConfiguration configuration) {
    LaunchType runType = ConfigurationHelper.getType(configuration);

    switch (runType) {
    case CLASS:
      return "class " + configuration.getName();
    case GROUP:
      return "groups";
    case SUITE:
      return "suite";
    case PACKAGE:
      return "package";
    default:
      return "from context";
    }
  }

  private String getTestNGLibraryVersion() {
    String testngLib = null;
    testngLib = ResourceUtil.getString("TestNG.jdk15.library"); //$NON-NLS-1$

    return testngLib;
  }

  private static void ppp(final Object msg) {
    TestNGPlugin
        .log(new Status(IStatus.INFO, TestNGPlugin.PLUGIN_ID, 1, String.valueOf(msg), null));
  }
}
