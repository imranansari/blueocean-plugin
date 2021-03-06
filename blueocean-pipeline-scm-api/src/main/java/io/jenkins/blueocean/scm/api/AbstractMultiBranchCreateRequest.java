package io.jenkins.blueocean.scm.api;

import com.cloudbees.plugins.credentials.domains.Domain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import hudson.model.Cause;
import hudson.model.Failure;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.User;
import io.jenkins.blueocean.commons.ErrorMessage;
import io.jenkins.blueocean.commons.ErrorMessage.Error;
import io.jenkins.blueocean.commons.ErrorMessage.Error.ErrorCodes;
import io.jenkins.blueocean.commons.ServiceException;
import io.jenkins.blueocean.credential.CredentialsUtils;
import io.jenkins.blueocean.rest.Reachable;
import io.jenkins.blueocean.rest.factory.BluePipelineFactory;
import io.jenkins.blueocean.rest.factory.organization.OrganizationFactory;
import io.jenkins.blueocean.rest.impl.pipeline.credential.BlueOceanCredentialsProvider;
import io.jenkins.blueocean.rest.impl.pipeline.credential.BlueOceanDomainRequirement;
import io.jenkins.blueocean.rest.model.BlueOrganization;
import io.jenkins.blueocean.rest.model.BluePipeline;
import io.jenkins.blueocean.rest.model.BlueScmConfig;
import jenkins.branch.BranchSource;
import jenkins.branch.MultiBranchProject;
import jenkins.branch.MultiBranchProjectDescriptor;
import jenkins.model.Jenkins;
import jenkins.model.ModifiableTopLevelItemGroup;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMProbeStat;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceEvent;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.pubsub.MessageException;
import org.jenkinsci.plugins.pubsub.PubsubBus;
import org.jenkinsci.plugins.pubsub.SimpleMessage;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Creates {@link MultiBranchProject}s with a single {@link SCMSource}
 */
public abstract class AbstractMultiBranchCreateRequest extends AbstractPipelineCreateRequest {
    private static final String DESCRIPTOR_NAME = "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject";

    private static final String ERROR_FIELD_SCM_CONFIG_URI = "scmConfig.uri";
    private static final String ERROR_FIELD_SCM_CONFIG_NAME = "scmConfig.name";
    private static final String ERROR_NAME = "name";
    private static final String ERROR_FIELD_SCM_CREDENTIAL_ID = "scm.credentialId";

    public AbstractMultiBranchCreateRequest(String name, BlueScmConfig scmConfig) {
        super(name, scmConfig);
    }

    @Override
    @SuppressWarnings("unchecked")
    public BluePipeline create(@Nonnull BlueOrganization organization, @Nonnull Reachable parent) throws IOException {
        validateInternal(getName(), scmConfig, organization);
        MultiBranchProject project = createMultiBranchProject(organization);
        assignCredentialToProject(scmConfig, project);
        SCMSource source = createSource(project, scmConfig);
        project.setSourcesList(ImmutableList.of(new BranchSource(source)));
        project.save();
        final boolean hasJenkinsfile = repoHasJenkinsFile(source);
        if(!hasJenkinsfile){
            sendMultibranchIndexingCompleteEvent(project, 5);
            AbstractScmSourceEvent scmSourceEvent = getScmSourceEvent(project, source);
            if(scmSourceEvent != null) {
                SCMSourceEvent.fireNow(scmSourceEvent);
            }
        }else{
            project.scheduleBuild(new Cause.UserIdCause());
        }
        return BluePipelineFactory.getPipelineInstance(project, OrganizationFactory.getInstance().getContainingOrg(project.getItemGroup()));
    }

    /**
     * @return Get {@link SCMSourceEvent}
     */
    protected @Nullable AbstractScmSourceEvent getScmSourceEvent(@Nonnull MultiBranchProject project, @Nonnull SCMSource source){
        return null;
    }

    /**
     * Create the source for the MultiBranchProject created with this request
     * @param project that was created
     * @param scmConfig config
     * @return valid SCMSource
     */
    protected abstract SCMSource createSource(@Nonnull MultiBranchProject project, @Nonnull BlueScmConfig scmConfig);

    /**
     * Validate the provided SCMConfig and test that a connection can be made to the SCM server
     * @param name of pipeline being created
     * @param scmConfig to validate
     * @return errors occuring during validation
     */
    protected abstract List<Error> validate(String name, BlueScmConfig scmConfig);

    public static class JenkinsfileCriteria implements SCMSourceCriteria {
        private static final long serialVersionUID = 1L;
        private AtomicBoolean jenkinsFileFound = new AtomicBoolean();

        @Override
        public boolean isHead(@Nonnull Probe probe, @Nonnull TaskListener listener) throws IOException {
            SCMProbeStat stat = probe.stat("Jenkinsfile");
            boolean foundJekinsFile =  stat.getType() != SCMFile.Type.NONEXISTENT && stat.getType() != SCMFile.Type.DIRECTORY;
            if(foundJekinsFile && !jenkinsFileFound.get()) {
                jenkinsFileFound.set(true);
            }
            return foundJekinsFile;
        }

        public boolean isJekinsfileFound() {
            return jenkinsFileFound.get();
        }
    }

    /**
     * Certain SCMSource can tell whether it can detect presence of Jenkinsfile across all branches
     * @param scmSource scm source
     * @return true as default. false if it can determine there is no Jenkinsfile in all branches
     */
    protected boolean repoHasJenkinsFile(@Nonnull SCMSource scmSource) {
        return true; //default
    }

    private void sendMultibranchIndexingCompleteEvent(final MultiBranchProject mbp, final int iterations) {
        Executors.newScheduledThreadPool(1).schedule(new Runnable() {
            @Override
            public void run() {
                _sendMultibranchIndexingCompleteEvent(mbp, iterations);
            }
        }, 1, TimeUnit.SECONDS);
    }

    private void _sendMultibranchIndexingCompleteEvent(MultiBranchProject mbp, int iterations) {
        try {
            SimpleMessage msg = new SimpleMessage();
            msg.set("jenkins_object_type", "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject");
            msg.set("job_run_status","ALLOCATED");
            msg.set("job_name",mbp.getName());
            msg.set("jenkins_org","jenkins");
            msg.set("job_orgfolder_indexing_status","COMPLETE");
            msg.set("job_run_queueId","1");
            msg.set("jenkins_object_name",mbp.getName());
            msg.set("blueocean_job_rest_url","/blue/rest/organizations/jenkins/pipelines/"+mbp.getName()+"/");
            msg.set("jenkins_event","job_run_queue_task_complete");
            msg.set("job_multibranch_indexing_result","SUCCESS");
            msg.set("blueocean_job_pipeline_name",mbp.getName());
            msg.set("jenkins_object_url","job/"+mbp.getName()+"/");
            msg.set("jenkins_channel","job");
            msg.set("jenkinsfile_present","false");
            PubsubBus.getBus().publish(msg);
        } catch (MessageException e) {
            throw new RuntimeException(e);
        }
    }

    private MultiBranchProject createMultiBranchProject(BlueOrganization organization) throws IOException {
        TopLevelItem item = createProject(getName(), DESCRIPTOR_NAME, MultiBranchProjectDescriptor.class, organization);
        if (!(item instanceof WorkflowMultiBranchProject)) {
            try {
                item.delete(); // we don't know about this project type
            } catch (InterruptedException e) {
                throw new ServiceException.UnexpectedErrorException("Failed to delete pipeline: " + getName());
            }
        }
        return (MultiBranchProject) item;
    }

    private void assignCredentialToProject(BlueScmConfig scmConfig, MultiBranchProject project) throws IOException {
        User authenticatedUser = User.current();
        String credentialId = computeCredentialId(scmConfig);
        if(StringUtils.isNotBlank(credentialId)) {
            Domain domain = CredentialsUtils.findDomain(credentialId, authenticatedUser);
            if(domain == null){
                throw new ServiceException.BadRequestException(
                    new ErrorMessage(400, "Failed to create pipeline")
                        .add(new Error(ERROR_FIELD_SCM_CREDENTIAL_ID,
                            Error.ErrorCodes.INVALID.toString(),
                            "No domain in user credentials found for credentialId: "+ credentialId)));
            }
            if (StringUtils.isEmpty(scmConfig.getUri())) {
                throw new ServiceException.BadRequestException("uri not specified");
            }
            if(domain.test(new BlueOceanDomainRequirement())) { //this is blueocean specific domain
                project.addProperty(
                    new BlueOceanCredentialsProvider.FolderPropertyImpl(authenticatedUser.getId(),
                        credentialId,
                        BlueOceanCredentialsProvider.createDomain(scmConfig.getUri())));
            }
        }
    }

    private void validateInternal(String name, BlueScmConfig scmConfig, BlueOrganization organization) {

        checkUserIsAuthenticatedAndHasItemCreatePermission(organization);

        // If scmConfig is empty then we are missing the uri and name
        if (scmConfig == null) {
            throw fail(new Error("scmConfig", ErrorCodes.MISSING.toString(), "scmConfig is required"));
        }

        if (scmConfig.getUri() == null) {
            throw fail(new Error(ERROR_FIELD_SCM_CONFIG_URI, ErrorCodes.MISSING.toString(), ERROR_FIELD_SCM_CONFIG_URI + " is required"));
        }

        if (getName() == null) {
            throw fail(new Error(ERROR_FIELD_SCM_CONFIG_NAME, ErrorCodes.MISSING.toString(), ERROR_FIELD_SCM_CONFIG_NAME + " is required"));
        }

        List<Error> errors = Lists.newLinkedList(validate(name, scmConfig));

        // Validate that name matches rules
        try {
            Jenkins.getInstance().getProjectNamingStrategy().checkName(getName());
        }catch (Failure f){
            errors.add(new Error(ERROR_FIELD_SCM_CONFIG_NAME, Error.ErrorCodes.INVALID.toString(),  getName() + " in not a valid name"));
        }

        ModifiableTopLevelItemGroup parent = getParent(organization);

        if (parent.getItem(name) != null) {
            errors.add(new Error(ERROR_NAME, Error.ErrorCodes.ALREADY_EXISTS.toString(), getName() + " already exists"));
        }

        if(!errors.isEmpty()){
            throw fail(errors);
        }
    }

    private static ServiceException fail(List<Error> errors) {
        ErrorMessage errorMessage = new ErrorMessage(400, "Failed to create pipeline");
        errorMessage.addAll(errors);
        return new ServiceException.BadRequestException(errorMessage);
    }

    private static ServiceException fail(Error... errors) {
        return fail(Arrays.asList(errors));
    }
}
