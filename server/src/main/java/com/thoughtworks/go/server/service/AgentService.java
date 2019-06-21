/*
 * Copyright 2019 ThoughtWorks, Inc.
 *
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
package com.thoughtworks.go.server.service;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.exceptions.GoConfigInvalidException;
import com.thoughtworks.go.config.update.AgentsUpdateValidator;
import com.thoughtworks.go.domain.AgentInstance;
import com.thoughtworks.go.domain.AllConfigErrors;
import com.thoughtworks.go.domain.ConfigErrors;
import com.thoughtworks.go.domain.NullAgent;
import com.thoughtworks.go.listener.AgentChangeListener;
import com.thoughtworks.go.listener.DatabaseEntityChangeListener;
import com.thoughtworks.go.remote.AgentIdentifier;
import com.thoughtworks.go.security.Registration;
import com.thoughtworks.go.server.domain.Agent;
import com.thoughtworks.go.server.domain.AgentInstances;
import com.thoughtworks.go.server.domain.ElasticAgentMetadata;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.messaging.notifications.AgentStatusChangeNotifier;
import com.thoughtworks.go.server.persistence.AgentDao;
import com.thoughtworks.go.server.service.result.HttpOperationResult;
import com.thoughtworks.go.server.service.result.LocalizedOperationResult;
import com.thoughtworks.go.server.service.result.OperationResult;
import com.thoughtworks.go.server.ui.AgentViewModel;
import com.thoughtworks.go.server.ui.AgentsViewModel;
import com.thoughtworks.go.server.util.UuidGenerator;
import com.thoughtworks.go.serverhealth.HealthStateScope;
import com.thoughtworks.go.serverhealth.HealthStateType;
import com.thoughtworks.go.serverhealth.ServerHealthService;
import com.thoughtworks.go.serverhealth.ServerHealthState;
import com.thoughtworks.go.util.SystemEnvironment;
import com.thoughtworks.go.util.TriState;
import com.thoughtworks.go.utils.Timeout;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;

import java.util.*;
import java.util.stream.Collectors;

import static com.thoughtworks.go.CurrentGoCDVersion.docsUrl;
import static com.thoughtworks.go.i18n.LocalizedMessage.entityConfigValidationFailed;
import static com.thoughtworks.go.util.ExceptionUtils.bombIfNull;
import static java.lang.String.format;


@Service
public class AgentService implements DatabaseEntityChangeListener<Agent> {
    private final SystemEnvironment systemEnvironment;
    private final SecurityService securityService;
    private final EnvironmentConfigService environmentConfigService;
    private final UuidGenerator uuidGenerator;
    private final ServerHealthService serverHealthService;
    private AgentStatusChangeNotifier agentStatusChangeNotifier;
    private GoConfigService goConfigService;
    private final AgentDao agentDao;

    private AgentInstances agentInstances;

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentService.class);

    @Autowired
    public AgentService(SystemEnvironment systemEnvironment, final EnvironmentConfigService environmentConfigService,
                        SecurityService securityService, AgentDao agentDao, UuidGenerator uuidGenerator, ServerHealthService serverHealthService,
                        final EmailSender emailSender, AgentStatusChangeNotifier agentStatusChangeNotifier, GoConfigService goConfigService) {
        this(systemEnvironment, null, environmentConfigService, securityService, agentDao, uuidGenerator, serverHealthService,
                agentStatusChangeNotifier, goConfigService);
        this.agentInstances = new AgentInstances(agentStatusChangeNotifier);
    }

    AgentService(SystemEnvironment systemEnvironment, AgentInstances agentInstances,
                 EnvironmentConfigService environmentConfigService, SecurityService securityService, AgentDao agentDao, UuidGenerator uuidGenerator,
                 ServerHealthService serverHealthService, AgentStatusChangeNotifier agentStatusChangeNotifier, GoConfigService goConfigService) {
        this.systemEnvironment = systemEnvironment;
        this.environmentConfigService = environmentConfigService;
        this.securityService = securityService;
        this.agentInstances = agentInstances;
        this.agentDao = agentDao;
        this.uuidGenerator = uuidGenerator;
        this.serverHealthService = serverHealthService;
        this.agentStatusChangeNotifier = agentStatusChangeNotifier;
        this.goConfigService = goConfigService;
    }

    public void initialize() {
        this.sync();
        agentDao.registerListener(this);
    }

    public void sync() {
        agentInstances.sync(new Agents(agentDao.getAllAgentConfigs()));
    }

    public AgentInstances agentInstances() {
        return agentInstances;
    }

    public Agents agents() {
        return new Agents(agentDao.getAllAgentConfigs());
    }

    public Map<AgentInstance, Collection<String>> agentEnvironmentMap() {
        Map<AgentInstance, Collection<String>> allAgents = new LinkedHashMap<>();
        for (AgentInstance agentInstance : agentInstances.allAgents()) {
            allAgents.put(agentInstance, environmentConfigService.environmentsFor(agentInstance.getUuid()));
        }
        return allAgents;
    }

    public Map<AgentInstance, Collection<EnvironmentConfig>> agentEnvironmentConfigsMap() {
        Map<AgentInstance, Collection<EnvironmentConfig>> allAgents = new LinkedHashMap<>();
        for (AgentInstance agentInstance : agentInstances.allAgents()) {
            allAgents.put(agentInstance, environmentConfigService.environmentConfigsFor(agentInstance.getUuid()));
        }
        return allAgents;
    }

    public AgentsViewModel registeredAgents() {
        return toAgentViewModels(agentInstances.findRegisteredAgents());
    }

    private AgentsViewModel toAgentViewModels(AgentInstances instances) {
        AgentsViewModel agents = new AgentsViewModel();
        for (AgentInstance instance : instances) {
            agents.add(toAgentViewModel(instance));
        }
        return agents;
    }

    private AgentViewModel toAgentViewModel(AgentInstance instance) {
        return new AgentViewModel(instance, environmentConfigService.environmentsFor(instance.getUuid()));
    }

    public AgentInstances findRegisteredAgents() {
        return agentInstances.findRegisteredAgents();
    }

    public List<Agent> findRegisteredAgentsInDB() {
        return agentDao.getAllAgents();
    }

    private boolean isUnknownAgent(AgentInstance agentInstance, OperationResult operationResult) {
        if (agentInstance.isNullAgent()) {
            String agentNotFoundMessage = String.format("Agent '%s' not found", agentInstance.getUuid());
            operationResult.notFound("Agent not found.", agentNotFoundMessage, HealthStateType.general(HealthStateScope.GLOBAL));
            return true;
        }
        return false;
    }

    private boolean hasOperatePermission(Username username, OperationResult operationResult) {
        if (!securityService.hasOperatePermissionForAgents(username)) {
            String message = "Unauthorized to operate on agent";
            operationResult.forbidden(message, message, HealthStateType.general(HealthStateScope.GLOBAL));
            return false;
        }
        return true;
    }

    public AgentInstance updateAgentAttributes(Username username, HttpOperationResult result, String uuid, String newHostname, String resources, String environments, TriState enable) {
        if (!hasOperatePermission(username, result)) {
            return null;
        }

        AgentInstance agentInstance = findAgent(uuid);
        if (isUnknownAgent(agentInstance, result)) {
            return null;
        }

        AgentConfig agentConfig;
        if (!hasAgent(uuid) && enable.isTrue()) {
            agentInstance = this.agentInstances.findAgent(uuid);
            agentConfig = agentInstance.agentConfig();
        } else {
            agentConfig = agentDao.agentConfigByUuid(uuid);
        }

        if (enable.isTrue()) {
            agentConfig.enable();
        }

        if (enable.isFalse()) {
            agentConfig.disable();
        }

        if (newHostname != null) {
            agentConfig.setHostname(newHostname);
        }

        if (resources != null) {
            agentConfig.setResources(new ResourceConfigs(resources));
        }

        if (environments != null) {
            agentConfig.setEnvironments(environments);
        }

        try {
            saveOrUpdate(agentConfig);

            if (agentConfig.hasErrors()) {
                result.unprocessibleEntity("Updating agent failed:", "", HealthStateType.general(HealthStateScope.GLOBAL));
            }
        } catch (Exception e){
            result.internalServerError("Updating agent failed: " + e.getMessage(), HealthStateType.general(HealthStateScope.GLOBAL));
            return null;
        }

        if (agentConfig != null) {
            return AgentInstance.createFromConfig(agentConfig, systemEnvironment, agentStatusChangeNotifier);
        }
        return null;
    }

    public void bulkUpdateAgentAttributes(Username username, LocalizedOperationResult result, List<String> uuids,
                                          List<String> resourcesToAdd, List<String> resourcesToRemove,
                                          List<String> environmentsToAdd, List<String> environmentsToRemove,
                                          TriState enable) {
        AgentsUpdateValidator agentsUpdateValidator = new AgentsUpdateValidator(agentInstances,
                username, result, uuids, environmentsToAdd, environmentsToRemove, enable, resourcesToAdd, resourcesToRemove, goConfigService);
        try {
            if (agentsUpdateValidator.canContinue()) {
                agentsUpdateValidator.validate();
                agentDao.bulkUpdateAttributes(uuids, resourcesToAdd, resourcesToRemove, environmentsToAdd, environmentsToRemove, enable, agentInstances);
                result.setMessage("Updated agent(s) with uuid(s): [" + StringUtils.join(uuids, ", ") + "].");
            }
        } catch (Exception e) {
            LOGGER.error("There was an error bulk updating agents", e);
            if (e instanceof GoConfigInvalidException && !result.hasMessage()) {
                result.unprocessableEntity(entityConfigValidationFailed(Agents.class.getAnnotation(ConfigTag.class).value(), StringUtils.join(uuids, ","), e.getMessage()));
            } else if (!result.hasMessage()) {
                result.internalServerError("Server error occured. Check log for details.");
            }
        }
    }

    private boolean populateAgentInstancesForUUIDs(OperationResult operationResult, List<String> uuids, List<AgentInstance> agents) {
        for (String uuid : uuids) {
            AgentInstance agentInstance = findAgentAndRefreshStatus(uuid);
            if (isUnknownAgent(agentInstance, operationResult)) {
                return false;
            }
            agents.add(agentInstance);
        }
        return true;
    }

    public void deleteAgents(Username username, HttpOperationResult operationResult, List<String> uuids) {
        if (!hasOperatePermission(username, operationResult)) {
            return;
        }
        List<AgentInstance> agents = new ArrayList<>();
        if (!populateAgentInstancesForUUIDs(operationResult, uuids, agents)) {
            return;
        }

        for (AgentInstance agentInstance : agents) {
            if (!agentInstance.canBeDeleted()) {
                operationResult.notAcceptable(String.format("Failed to delete %s agent(s), as agent(s) might not be disabled or are still building.", agents.size()),
                        HealthStateType.general(HealthStateScope.GLOBAL));
                return;
            }
        }

        try {
            List<Agent> allAgents = agentDao.getAllAgents(uuids);
            if (allAgents.size() != uuids.size()) {
                List<String> uuidsOfAgentsInDatabase = allAgents.stream().map(agent -> agent.getUuid()).collect(Collectors.toList());
                List<String> nonExistentAgentIds = uuids.stream().filter(uuid -> !uuidsOfAgentsInDatabase.contains(uuid)).collect(Collectors.toList());
                //TODO : Revisit this for checking whether to throw this error
//                if (nonExistentAgentIds != null) {
//                    bomb("Unable to delete agent; Agent [" + uuid + "] not found.");
//                }
            }
            agentDao.bulkSoftDelete(uuids);
            operationResult.ok(String.format("Deleted %s agent(s).", agents.size()));
        } catch (Exception e) {
            operationResult.internalServerError("Deleting agents failed:" + e.getMessage(), HealthStateType.general(HealthStateScope.GLOBAL));
        }
    }

    public void updateRuntimeInfo(AgentRuntimeInfo info) {
        if (!info.hasCookie()) {
            LOGGER.warn("Agent [{}] has no cookie set", info.agentInfoDebugString());
            throw new AgentNoCookieSetException(format("Agent [%s] has no cookie set", info.agentInfoDebugString()));
        }
        if (info.hasDuplicateCookie(agentDao.cookieFor(info.getIdentifier()))) {
            LOGGER.warn("Found agent [{}] with duplicate uuid. Please check the agent installation.", info.agentInfoDebugString());
            serverHealthService.update(
                    ServerHealthState.warning(format("[%s] has duplicate unique identifier which conflicts with [%s]", info.agentInfoForDisplay(), findAgentAndRefreshStatus(info.getUUId()).agentInfoForDisplay()),
                            "Please check the agent installation. Click <a href='" + docsUrl("/faq/agent_guid_issue.html") + "' target='_blank'>here</a> for more info.",
                            HealthStateType.duplicateAgent(HealthStateScope.forAgent(info.getCookie())), Timeout.THIRTY_SECONDS));
            throw new AgentWithDuplicateUUIDException(format("Agent [%s] has invalid cookie", info.agentInfoDebugString()));
        }
        AgentInstance agentInstance = findAgentAndRefreshStatus(info.getUUId());
        if (agentInstance.isIpChangeRequired(info.getIpAdress())) {
            AgentConfig agentConfig = agentInstance.agentConfig();
            LOGGER.warn("Agent with UUID [{}] changed IP Address from [{}] to [{}]", info.getUUId(), agentConfig.getIpAddress(), info.getIpAdress());
            String uuid = agentConfig.getUuid();

            Agent agent = agentDao.agentByUuid(uuid);
            bombIfNull(agent, "Unable to set agent ipAddress; Agent [" + uuid + "] not found.");
            agent.setIpaddress(info.getIpAdress());
            saveOrUpdate(agent);
        }
        agentInstances.updateAgentRuntimeInfo(info);
    }


    public Username agentUsername(String uuId, String ipAddress, String hostNameForDisplay) {
        return new Username(String.format("agent_%s_%s_%s", uuId, ipAddress, hostNameForDisplay));
    }

    public Registration requestRegistration(Username username, AgentRuntimeInfo agentRuntimeInfo) {
        LOGGER.debug("Agent is requesting registration {}", agentRuntimeInfo);
        AgentInstance agentInstance = agentInstances.register(agentRuntimeInfo);
        Registration registration = agentInstance.assignCertification();
        if (agentInstance.isRegistered()) {
            AgentConfig agentConfig = agentInstance.agentConfig();
            if (agentConfig.getCookie() == null) {
                String cookie = uuidGenerator.randomUuid();
                agentConfig.setCookie(cookie);
            }
            saveOrUpdateAgentInstance(agentInstance, username);
            if (agentConfig.hasErrors()) {
                List<ConfigErrors> errors = ErrorCollector.getAllErrors(agentConfig);

                throw new GoConfigInvalidException(null, new AllConfigErrors(errors));
            }
            LOGGER.debug("New Agent approved {}", agentRuntimeInfo);
        }
        return registration;
    }

    public void updateAgentApprovalStatus(final String uuid, final Boolean isDenied, Username currentUser) {
        Agent agent = agentDao.agentByUuid(uuid);
        agent.setDisabled(isDenied);
        saveOrUpdate(agent);
    }

    public void saveOrUpdateAgentInstance(AgentInstance agentInstance, Username currentUser) {
        AgentConfig agentConfig = agentInstance.agentConfig();
        if (agentDao.agentByUuid(agentConfig.getUuid()) != null) {
            this.updateAgentApprovalStatus(agentConfig.getUuid(), agentConfig.isDisabled(), currentUser);
        } else {
            saveOrUpdate(agentConfig);
        }
    }

    @Deprecated
    public void approve(String uuid) {
        AgentInstance agentInstance = findAgentAndRefreshStatus(uuid);
        agentInstance.enable();
        if (hasAgent(agentInstance.getUuid())) {
            LOGGER.warn("Registered agent with the same uuid [{}] already approved.", agentInstance);
        } else {
            saveOrUpdate(agentInstance.agentConfig());
        }
    }

    public void notifyJobCancelledEvent(String agentId) {
        agentInstances.updateAgentAboutCancelledBuild(agentId, true);
    }

    public AgentInstance findAgentAndRefreshStatus(String uuid) {
        return agentInstances.findAgentAndRefreshStatus(uuid);
    }

    public AgentInstance findAgent(String uuid) {
        return agentInstances.findAgent(uuid);
    }

    public void clearAll() {
        agentInstances.clearAll();
    }

    /**
     * called from spring timer
     */
    public void refresh() {
        agentInstances.refresh();
    }

    public void building(String uuid, AgentBuildingInfo agentBuildingInfo) {
        agentInstances.building(uuid, agentBuildingInfo);
    }

    public String assignCookie(AgentIdentifier identifier) {
        String cookie = uuidGenerator.randomUuid();
        agentDao.associateCookie(identifier, cookie);
        return cookie;
    }

    public Agent findAgentObjectByUuid(String uuid) {
        return agentDao.agentByUuid(uuid);
    }

    public AgentsViewModel filter(List<String> uuids) {
        AgentsViewModel viewModels = new AgentsViewModel();
        for (AgentInstance agentInstance : agentInstances.filter(uuids)) {
            viewModels.add(new AgentViewModel(agentInstance));
        }
        return viewModels;
    }

    public AgentViewModel findAgentViewModel(String uuid) {
        return toAgentViewModel(findAgentAndRefreshStatus(uuid));
    }

    public LinkedMultiValueMap<String, ElasticAgentMetadata> allElasticAgents() {
        return agentInstances.allElasticAgentsGroupedByPluginId();
    }

    public AgentInstance findElasticAgent(String elasticAgentId, String elasticPluginId) {
        return agentInstances.findElasticAgent(elasticAgentId, elasticPluginId);
    }

    public AgentInstances findEnabledAgents() {
        return agentInstances.findEnabledAgents();
    }

    public AgentInstances findDisabledAgents() {
        return agentInstances.findDisabledAgents();
    }

    public void register(AgentConfig agentConfig, String agentAutoRegisterResources, String agentAutoRegisterEnvironments, HttpOperationResult result) {
        if (agentConfig.getCookie() == null) {
            String cookie = uuidGenerator.randomUuid();
            agentConfig.setCookie(cookie);
        }
        agentConfig.setResourceConfigs(new ResourceConfigs(agentAutoRegisterResources));
        agentConfig.setEnvironments(agentAutoRegisterEnvironments);
        saveOrUpdate(agentConfig);
    }

    public boolean hasAgent(String uuid) {
        return agentDao.agentByUuid(uuid) != null;
    }

    public AgentConfig agentByUuid(String agentUuid) {
        AgentConfig agentConfig = agentDao.agentConfigByUuid(agentUuid);
        if(agentConfig == null){
            agentConfig = NullAgent.createNullAgent();
        }
        return agentConfig;
    }

    public List<String> allAgentUuids() {
        return agentDao.allAgentUuids();
    }
    public void disableAgents(Username currentUser, AgentInstance... agentInstance) {
        disableAgents(true, currentUser, agentInstance);
    }

    private void disableAgents(boolean disabled, Username currentUser, AgentInstance... instances) {
        List<String> uuids = Arrays.stream(instances).map(instance -> instance.getUuid()).collect(Collectors.toList());
        agentDao.changeDisabled(uuids, disabled);
    }

    public void saveOrUpdate(AgentConfig agentConfig) {
        agentConfig.validate(null);
        if (!agentConfig.hasErrors()) {
            agentDao.saveOrUpdate(agentConfig);
        }
    }

    public void saveOrUpdate(Agent agent) {
        agent.validate(null);
        if (!agent.hasErrors()) {
            agentDao.saveOrUpdate(agent);
        }
    }

    @Override
    public void onBulkEntityChange() {

    }

    @Override
    public void onEntityChange(Agent entity) {

    }
}
