/*
 * Copyright (C) 2010 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.hudson.maven3;

import com.google.common.collect.Maps;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.model.Run;
import hudson.remoting.Which;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.Maven;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.BuildInfoProperties;
import org.jfrog.build.client.ClientProperties;
import org.jfrog.build.extractor.maven.AbstractPropertyResolver;
import org.jfrog.build.extractor.maven.BuildInfoRecorder;
import org.jfrog.hudson.maven3.util.ActionableHelper;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Map;

/**
 * Maven3 builder.
 *
 * @author Yossi Shaul
 */
public class Maven3Builder extends Builder {

    public static final String CLASSWORLDS_LAUNCHER = "org.codehaus.plexus.classworlds.launcher.Launcher";

    private final String mavenName;
    private final String rootPom;
    private final String goals;
    private final String mavenOpts;

    @DataBoundConstructor
    public Maven3Builder(String mavenName, String rootPom, String goals, String mavenOpts) {
        this.mavenName = mavenName;
        this.rootPom = rootPom;
        this.goals = goals;
        this.mavenOpts = mavenOpts;
    }

    public String getMavenName() {
        return mavenName;
    }

    public String getRootPom() {
        return rootPom;
    }

    public String getGoals() {
        return goals;
    }

    public String getMavenOpts() {
        return mavenOpts;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        EnvVars env = build.getEnvironment(listener);
        FilePath workDir = build.getModuleRoot();
        ArgumentListBuilder cmdLine = buildMavenCmdLine(build, launcher, listener);
        String[] cmds = cmdLine.toCommandArray();
        try {
            //listener.getLogger().println("Executing: " + cmdLine.toStringWithQuote());
            int exitValue = launcher.launch().cmds(cmds).envs(env).stdout(listener).pwd(workDir).join();
            boolean success = (exitValue == 0);
            build.setResult(success ? Result.SUCCESS : Result.FAILURE);
            return success;
        } catch (IOException e) {
            Util.displayIOException(e, listener);
            e.printStackTrace(listener.fatalError("command execution failed"));
            build.setResult(Result.FAILURE);
            return false;
        }
    }


    private ArgumentListBuilder buildMavenCmdLine(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {
        Maven.MavenInstallation mvn = getMavenInstallation();
        if (mvn == null) {
            listener.error("Maven version is not configured for this project. Can't determine which Maven to run");
            throw new Run.RunnerAbortedException();
        }
        if (mvn.getHome() == null) {
            listener.error("Maven '%s' doesn't have its home set", mvn.getName());
            throw new Run.RunnerAbortedException();
        }

        ArgumentListBuilder args = new ArgumentListBuilder();

        if (!launcher.isUnix()) {
            args.add("cmd.exe", "/C");
        }

        // java
        String java = build.getProject().getJDK() != null ? build.getProject().getJDK().getBinDir() + "/java" : "java";
        args.add(new File(java).getAbsolutePath());

        // maven opts
        args.addTokenized(getMavenOpts());

        File bootDir = new File(mvn.getHomeDir(), "boot");
        File[] candidates = bootDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("plexus-classworlds");
            }
        });
        if (candidates == null || candidates.length == 0) {
            listener.error("Couldn't find classworlds jar under " + bootDir.getAbsolutePath());
            throw new Run.RunnerAbortedException();
        }

        File classWorldsJar = candidates[0];

        // classpath
        args.add("-cp");
        String cpSeparator = launcher.isUnix() ? ":" : ";";

        StringBuilder cpBuilder = new StringBuilder();
        cpBuilder.append(classWorldsJar.getAbsolutePath());

        getClass().getClassLoader().getResource("classworlds.conf");

        // extractor jars
        File extractorJar = Which.jarFile(AbstractPropertyResolver.class);
        if (extractorJar == null || !extractorJar.exists()) {
            listener.error("Couldn't find maven3 extractor jar (class not found: " +
                    AbstractPropertyResolver.class.getName() + ").");
            throw new Run.RunnerAbortedException();
        }

        File extractorLibDir = extractorJar.getParentFile();
        for (File extractorDependency : extractorLibDir.listFiles()) {
            //cpBuilder.append(cpSeparator).append(extractorDependency.getAbsolutePath());
        }
        args.add(cpBuilder.toString());

        // maven home
        args.add("-Dmaven.home=" + mvn.getHome());

        // classworlds conf
        args.add("-Dm3plugin.lib=" + extractorLibDir);
        File pluginClasses = Which.jarFile(this.getClass());
        File classworldsConf = new File(pluginClasses, "classworlds.conf");
        if (!classworldsConf.exists()) {
            listener.error(
                    "Unable to locate classworlds configuration file under " + classworldsConf.getAbsolutePath());
            throw new Run.RunnerAbortedException();
        }
        args.add("-Dclassworlds.conf=" + classworldsConf.getAbsolutePath());

        addBuilderInfoArguments(args, build, listener);

        // maven opts
        args.add(Util.replaceMacro(mavenOpts, build.getBuildVariableResolver()));

        // classworlds launcher main class
        args.add(CLASSWORLDS_LAUNCHER);

        // pom file to build
        args.add("-f", rootPom);

        // maven goals
        args.addTokenized(goals);

        return args;
    }


    private void addBuilderInfoArguments(ArgumentListBuilder args, AbstractBuild<?, ?> build, BuildListener listener)
            throws IOException, InterruptedException {

        Map<String, String> props = Maps.newHashMap();

        props.put(BuildInfoRecorder.ACTIVATE_RECORDER, Boolean.TRUE.toString());

        String buildName = build.getDisplayName();
        props.put(BuildInfoProperties.PROP_BUILD_NAME, buildName);
        props.put(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX + "build.name", buildName);

        String buildNumber = build.getNumber() + "";
        props.put(BuildInfoProperties.PROP_BUILD_NUMBER, buildNumber);
        props.put(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX + "build.number", buildName);

        // TODO: what is the expected format?
        String buildStarted = build.getTimestamp().getTimeInMillis() + "";
        props.put(BuildInfoProperties.PROP_BUILD_STARTED, buildStarted);

        EnvVars envVars = build.getEnvironment(listener);
        String vcsRevision = envVars.get("SVN_REVISION");
        if (StringUtils.isNotBlank(vcsRevision)) {
            props.put(BuildInfoProperties.PROP_VCS_REVISION, vcsRevision);
            props.put(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX +
                    BuildInfoProperties.PROP_VCS_REVISION, vcsRevision);
        }

        String buildUrl = Hudson.getInstance().getRootUrl() + build.getUrl();
        props.put(BuildInfoProperties.PROP_BUILD_URL, buildUrl);

        Cause.UpstreamCause parent = ActionableHelper.getUpstreamCause(build);
        if (parent != null) {
            String parentProject = parent.getUpstreamProject();
            props.put(BuildInfoProperties.PROP_PARENT_BUILD_NAME, parentProject);
            props.put(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX +
                    BuildInfoProperties.PROP_PARENT_BUILD_NAME, parentProject);

            String parentBuildName = parent.getUpstreamBuild() + "";
            props.put(BuildInfoProperties.PROP_PARENT_BUILD_NUMBER, parentBuildName);
            props.put(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX +
                    BuildInfoProperties.PROP_PARENT_BUILD_NUMBER, parentBuildName);
        }

        CauseAction action = ActionableHelper.getLatestAction(build, CauseAction.class);
        if (action != null) {
            for (Cause cause : action.getCauses()) {
                if (cause instanceof Cause.UserCause) {
                    String userName = ((Cause.UserCause) cause).getUserName();
                    props.put(BuildInfoProperties.PROP_PRINCIPAL, userName);
                }
            }
        }

        props.put(BuildInfoProperties.PROP_AGENT_NAME, "hudson");
        props.put(BuildInfoProperties.PROP_AGENT_VERSION, build.getHudsonVersion());

        //props.put(ClientProperties.PROP_CONTEXT_URL, serverConfig.getUrl());
        //props.put(ClientProperties.PROP_TIMEOUT, Integer.toString(serverConfig.getTimeout()));
        //props.put(ClientProperties.PROP_PUBLISH_REPOKEY, builder.getDeployableRepo());

        /*String deployerUsername = builder.getDeployerUsername();
        if (StringUtils.isNotBlank(deployerUsername)) {
            props.put(ClientProperties.PROP_PUBLISH_USERNAME, deployerUsername);
            props.put(ClientProperties.PROP_PUBLISH_PASSWORD, builder.getDeployerPassword());
        }*/

        props.put(ClientProperties.PROP_PUBLISH_ARTIFACT, Boolean.FALSE.toString());
        props.put(ClientProperties.PROP_PUBLISH_BUILD_INFO, Boolean.FALSE.toString());
        File outputFile = new File(build.getRootDir(), "maven3/buildinfo.json");
        outputFile.getParentFile().mkdirs();
        props.put(BuildInfoProperties.PROP_BUILD_INFO_OUTPUT_FILE, outputFile.getAbsolutePath());

        args.addKeyValuePairs("-D", props);
    }

    public Maven.MavenInstallation getMavenInstallation() {
        Maven.MavenInstallation[] installations = getDescriptor().getInstallations();
        for (Maven.MavenInstallation installation : installations) {
            if (installation.getName().equals(mavenName)) {
                return installation;
            }
        }
        // not found, return the first installation if exists
        return installations.length > 0 ? installations[0] : null;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }

        protected DescriptorImpl(Class<? extends Maven3Builder> clazz) {
            super(clazz);
        }

        /**
         * Obtains the {@link Maven.MavenInstallation.DescriptorImpl} instance.
         */
        public Maven.MavenInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(Maven.MavenInstallation.DescriptorImpl.class);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getHelpFile() {
            return "/plugin/maven3/help.html";
        }

        @Override
        public String getDisplayName() {
            return Messages.step_displayName();
        }

        public Maven.DescriptorImpl getMavenDescriptor() {
            return Hudson.getInstance().getDescriptorByType(Maven.DescriptorImpl.class);
        }

        public Maven.MavenInstallation[] getInstallations() {
            return getMavenDescriptor().getInstallations();
        }

        @Override
        public Maven3Builder newInstance(StaplerRequest request, JSONObject formData) throws FormException {
            return (Maven3Builder) request.bindJSON(clazz, formData);
        }

    }

}
