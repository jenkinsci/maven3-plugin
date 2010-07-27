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

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.Maven;
import hudson.tools.ToolInstallation;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;

/**
 * Maven3 builder.
 *
 * @author Yossi Shaul
 */
public class Maven3Builder extends Builder {

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
        throw new UnsupportedOperationException("not implemented yet");
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
