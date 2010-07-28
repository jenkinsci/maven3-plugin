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

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.Maven;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;

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
        args.add(classWorldsJar.getAbsolutePath());

        // extractor jars
        //args.add(cpSeparator + Which.jarFile(BuildInfoRecorderLifecycleParticipant.class).getAbsolutePath());

        // maven home
        args.add("-Dmaven.home=" + mvn.getHome());

        // classworlds conf
        args.add("-Dclassworlds.conf=" + mvn.getHome() + "/bin/m2.conf");

        // maven opts
        args.add(Util.replaceMacro(mavenOpts, build.getBuildVariableResolver()));

        // classworlds launcher main class
        args.add(CLASSWORLDS_LAUNCHER);

        // maven goals
        args.add(goals);

        return args;
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
