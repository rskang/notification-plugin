/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tikal.hudson.plugins.notification;

import com.tikal.hudson.plugins.notification.model.BuildState;
import com.tikal.hudson.plugins.notification.model.JobState;
import com.tikal.hudson.plugins.notification.model.UserData;
import com.tikal.hudson.plugins.notification.model.ScmState;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.*;
import jenkins.model.Jenkins;

import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import hudson.scm.SubversionChangeLogSet.LogEntry;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;


@SuppressWarnings({ "unchecked", "rawtypes" })
public enum Phase {
    STARTED, COMPLETED, FINALIZED;

    @SuppressWarnings( "CastToConcreteClass" )
    public void handle(Run run, TaskListener listener) {

        HudsonNotificationProperty property = (HudsonNotificationProperty) run.getParent().getProperty(HudsonNotificationProperty.class);
        if ( property == null ){ return; }

        for ( Endpoint target : property.getEndpoints()) {
            if ( isRun( target )) {
                listener.getLogger().println( String.format( "Notifying endpoint '%s'", target ));

                try {
                    JobState jobState = buildJobState(run.getParent(), run, listener, target);
                    EnvVars environment = run.getEnvironment(listener);
                    String expandedUrl = environment.expand(target.getUrl());
                    target.getProtocol().send(expandedUrl,
                                              target.getFormat().serialize(jobState),
                                              target.getTimeout(),
                                              target.isJson());
                } catch (Throwable error) {
                    error.printStackTrace( listener.error( String.format( "Failed to notify endpoint '%s'", target )));
                    listener.getLogger().println( String.format( "Failed to notify endpoint '%s' - %s: %s",
                                                                 target, error.getClass().getName(), error.getMessage()));
                }
            }
        }
    }


    /**
     * Determines if the endpoint specified should be notified at the current job phase.
     */
    private boolean isRun( Endpoint endpoint ) {
        String event = endpoint.getEvent();
        return (( event == null ) || event.equals( "all" ) || event.equals( this.toString().toLowerCase()));
    }

    private JobState buildJobState(Job job, Run run, TaskListener listener, Endpoint target)
        throws IOException, InterruptedException
    {

        Jenkins            jenkins      = Jenkins.getInstance();
        String             rootUrl      = jenkins.getRootUrl();
        JobState           jobState     = new JobState();
        BuildState         buildState   = new BuildState();
        UserData           userData     = new UserData();
        Result             result       = run.getResult();
        ParametersAction   paramsAction = run.getAction(ParametersAction.class);
        EnvVars            environment  = run.getEnvironment( listener );
        StringBuilder      log          = this.getLog(run, target);

        jobState.setName( job.getName());
        String fullName = job.getFullDisplayName();
        // This is to replace the potential characters used by CloudBees folder
        // plugin.
        String cleanStr = fullName.replaceAll(" \u00BB ", "/");
        jobState.setFullName( cleanStr );
        jobState.setUrl( job.getUrl());
        jobState.setBuild( buildState );

        buildState.setNumber( run.number );
        buildState.setUrl( run.getUrl());
        buildState.setPhase( this );
        buildState.setUserData( userData );
        buildState.setLog( log );
        buildState.setStartedAt( run.getTimeInMillis() );
        buildState.setDuration( run.getDuration() );
        buildState.setFinishedAt( run.getTimeInMillis() + run.getDuration() );

        if ( result != null ) {
            buildState.setStatus(result.toString());
        }

        if ( rootUrl != null ) {
            buildState.setFullUrl(rootUrl + run.getUrl());
        }

        
        buildState.updateArtifacts( job, run );
        buildState.updateScmChanges( run );
        buildState.updateTestResults( run );

        if ( paramsAction != null ) {
            EnvVars env = new EnvVars();
            for (ParameterValue value : paramsAction.getParameters()){
                if ( ! value.isSensitive()) {
                    value.buildEnvironment( run, env );
                }
            }
            buildState.setParameters(env);
        }

        /**
         * Read a properties file if it exists and add it to the data.
         * @TODO make the filename configurable
         * @TODO Move this into an update Method (like artifacts) 
         */
        FilePath propsFile = new FilePath((( AbstractBuild ) run ).getWorkspace(), "notification.properties");
        if ( propsFile.exists() ) {
            Properties props = new Properties();
            props.load( propsFile.read());
            Map<String, String> key_pairs = new HashMap<String, String>();
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                listener.getLogger().println( String.format("The grade in " + key + " is: " + value));
                key_pairs.put( key, value );
            }
            userData.setUserData( key_pairs );
        } else {
            listener.getLogger().println( String.format("notification.properties does not exist" ));
        }

        return jobState;
    }

    private StringBuilder getLog(Run run, Endpoint target) {
        StringBuilder log = new StringBuilder("");
        Integer loglines = target.getLoglines();

        if (null == loglines || loglines == 0) {
            log.append("Log is not available or you have requested no log data.");
            return log;
        }

        try {
            switch (loglines) {
                // The full log
                case -1:
                    log.append(run.getLog());
                    break;
                default:
                    List<String> logEntries = run.getLog(loglines);
                    for (String entry: logEntries) {
                        log.append(entry);
                        log.append("\n");
                    }
            }
        } catch (IOException e) {
            log.append("Unable to retrieve log");
        }
        return log;
    }
}
