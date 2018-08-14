/*
 * Copyright 2016 Netflix, Inc.
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
package com.netflix.conductor.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.netflix.conductor.core.execution.ApplicationException;
import com.netflix.conductor.service.utils.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.annotations.Trace;
import com.netflix.conductor.common.metadata.events.EventExecution;
import com.netflix.conductor.common.metadata.tasks.PollData;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.Task.Status;
import com.netflix.conductor.common.metadata.tasks.TaskExecLog;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.run.SearchResult;
import com.netflix.conductor.common.run.TaskSummary;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.run.WorkflowSummary;
import com.netflix.conductor.core.config.Configuration;
import com.netflix.conductor.core.events.queue.Message;
import com.netflix.conductor.core.execution.SystemTaskType;
import com.netflix.conductor.core.execution.WorkflowExecutor;
import com.netflix.conductor.core.utils.QueueUtils;
import com.netflix.conductor.dao.ExecutionDAO;
import com.netflix.conductor.dao.IndexDAO;
import com.netflix.conductor.dao.MetadataDAO;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.metrics.Monitors;

import java.util.Collections;

/**
 *
 * @author visingh
 * @author Viren
 *
 */
@Singleton
@Trace
public class ExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionService.class);

    private final WorkflowExecutor executor;

    private final ExecutionDAO executionDAO;

    private final IndexDAO indexer;

    private final QueueDAO queue;

    private final MetadataDAO metadata;

    private final int taskRequeueTimeout;

    private final int maxSearchSize;

    private static final int MAX_POLL_TIMEOUT_MS = 5000;

    private static final int POLL_COUNT_ONE = 1;

    private static final int POLLING_TIMEOUT_IN_MS = 100;

	@Inject
	public ExecutionService(WorkflowExecutor wfProvider, ExecutionDAO executionDAO, QueueDAO queue, MetadataDAO metadata, IndexDAO indexer, Configuration config) {
		this.executor = wfProvider;
		this.executionDAO = executionDAO;
		this.queue = queue;
		this.metadata = metadata;
		this.indexer = indexer;
		this.taskRequeueTimeout = config.getIntProperty("task.requeue.timeout", 60_000);
        this.maxSearchSize = config.getIntProperty("workflow.max.search.size", 5_000);
	}

	public Task poll(String taskType, String workerId) throws Exception {
		return poll(taskType, workerId, null);
	}
	public Task poll(String taskType, String workerId, String domain) throws Exception {

		List<Task> tasks = poll(taskType, workerId, domain, 1, 100);
		if(tasks.isEmpty()) {
			return null;
		}
		return tasks.get(0);
	}

	public List<Task> poll(String taskType, String workerId, int count, int timeoutInMilliSecond) throws Exception {
		return poll(taskType, workerId, null, count, timeoutInMilliSecond);
	}

	public List<Task> poll(String taskType, String workerId, String domain, int count, int timeoutInMilliSecond) {
		if (timeoutInMilliSecond > MAX_POLL_TIMEOUT_MS) {
			throw new ApplicationException(ApplicationException.Code.INVALID_INPUT,
                    "Long Poll Timeout value cannot be more than 5 seconds");
		}
		String queueName = QueueUtils.getQueueName(taskType, domain);

		List<String> taskIds = queue.pop(queueName, count, timeoutInMilliSecond);
		List<Task> tasks = new LinkedList<>();
		for(String taskId : taskIds) {
			Task task = getTask(taskId);
			if(task == null) {
				continue;
			}

			if(executionDAO.exceedsInProgressLimit(task)) {
				continue;
			}

			task.setStatus(Status.IN_PROGRESS);
			if (task.getStartTime() == 0) {
				task.setStartTime(System.currentTimeMillis());
				Monitors.recordQueueWaitTime(task.getTaskDefName(), task.getQueueWaitTime());
			}
			task.setWorkerId(workerId);
			task.setPollCount(task.getPollCount() + 1);
			executionDAO.updateTask(task);
			tasks.add(task);
		}
		executionDAO.updateLastPoll(taskType, domain, workerId);
		Monitors.recordTaskPoll(queueName);
		return tasks;
	}

	public Task getLastPollTask(String taskType, String workerId, String domain) {
		List<Task> tasks = poll(taskType, workerId, domain, POLL_COUNT_ONE, POLLING_TIMEOUT_IN_MS);
		if (tasks.isEmpty()) {
			logger.debug("No Task available for the poll: /tasks/poll/{}?{}&{}", taskType, workerId, domain);
			return null;
		}
		Task task = tasks.get(0);
		logger.debug("The Task {} being returned for /tasks/poll/{}?{}&{}", task, taskType, workerId, domain);
		return task;
	}

	public List<PollData> getPollData(String taskType) {
		return executionDAO.getPollData(taskType);
	}

	public List<PollData> getAllPollData() {
		Map<String, Long> queueSizes = queue.queuesDetail();
		List<PollData> allPollData = new ArrayList<PollData>();
		queueSizes.keySet().forEach(k -> {
			try {
				if(!k.contains(QueueUtils.DOMAIN_SEPARATOR)){
					allPollData.addAll(getPollData(QueueUtils.getQueueNameWithoutDomain(k)));
				}
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		});
		return allPollData;

	}

	//For backward compatibility - to be removed in the later versions
	public void updateTask(Task task) {
		updateTask(new TaskResult(task));
	}

	public void updateTask(TaskResult taskResult) {
		executor.updateTask(taskResult);
	}

	public List<Task> getTasks(String taskType, String startKey, int count) {
		return executor.getTasks(taskType, startKey, count);
	}

	public Task getTask(String taskId) {
		return executionDAO.getTask(taskId);
	}

	public Task getPendingTaskForWorkflow(String taskReferenceName, String workflowId) {
		return executor.getPendingTaskByWorkflow(taskReferenceName, workflowId);
	}

	/**
	 * This method removes the task from the un-acked Queue
	 *
	 * @param taskId: the taskId that needs to be updated and removed from the unacked queue
	 * @return True in case of successful removal of the taskId from the un-acked queue
	 */
	public boolean ackTaskReceived(String taskId) {
		return Optional.ofNullable(getTask(taskId))
				.map(QueueUtils::getQueueName)
				.map(queueName -> queue.ack(queueName, taskId))
				.orElse(false);
	}

	public Map<String, Integer> getTaskQueueSizes(List<String> taskDefNames) {
		Map<String, Integer> sizes = new HashMap<>();
		for (String taskDefName : taskDefNames) {
			sizes.put(taskDefName, queue.getSize(taskDefName));
		}
		return sizes;
	}

	public void removeTaskfromQueue(String taskType, String taskId) {
		Task task = executionDAO.getTask(taskId);
		queue.remove(QueueUtils.getQueueName(task), taskId);
	}

	public int requeuePendingTasks() {
		long threshold = System.currentTimeMillis() - taskRequeueTimeout;
		List<WorkflowDef> workflowDefs = metadata.getAll();
		int count = 0;
		for (WorkflowDef workflowDef : workflowDefs) {
			List<Workflow> workflows = executor.getRunningWorkflows(workflowDef.getName());
			for (Workflow workflow : workflows) {
				count += requeuePendingTasks(workflow, threshold);
			}
		}
		return count;
	}

	private int requeuePendingTasks(Workflow workflow, long threshold) {

		int count = 0;
		List<Task> tasks = workflow.getTasks();
		for (Task pending : tasks) {
			if (SystemTaskType.is(pending.getTaskType())) {
				continue;
			}
			if (pending.getStatus().isTerminal()) {
				continue;
			}
			if (pending.getUpdateTime() < threshold) {
				logger.info("Requeuing Task: workflowId=" + workflow.getWorkflowId() + ", taskType=" + pending.getTaskType() + ", taskId="
						+ pending.getTaskId());
				long callback = pending.getCallbackAfterSeconds();
				if (callback < 0) {
					callback = 0;
				}
				boolean pushed = queue.pushIfNotExists(QueueUtils.getQueueName(pending), pending.getTaskId(), callback);
				if (pushed) {
					count++;
				}
			}
		}
		return count;
	}

	public int requeuePendingTasks(String taskType) {

		int count = 0;
		List<Task> tasks = getPendingTasksForTaskType(taskType);

		for (Task pending : tasks) {

			if (SystemTaskType.is(pending.getTaskType())) {
				continue;
			}
			if (pending.getStatus().isTerminal()) {
				continue;
			}

			logger.info("Requeuing Task: workflowId=" + pending.getWorkflowInstanceId() + ", taskType=" + pending.getTaskType() + ", taskId=" + pending.getTaskId());
			boolean pushed = requeue(pending);
			if (pushed) {
				count++;
			}

		}
		return count;
	}

	private boolean requeue(Task pending) {
		long callback = pending.getCallbackAfterSeconds();
		if (callback < 0) {
			callback = 0;
		}
		queue.remove(QueueUtils.getQueueName(pending), pending.getTaskId());
		long now = System.currentTimeMillis();
		callback = callback - ((now - pending.getUpdateTime())/1000);
		if(callback < 0) {
			callback = 0;
		}
		return queue.pushIfNotExists(QueueUtils.getQueueName(pending), pending.getTaskId(), callback);
	}

	public List<Workflow> getWorkflowInstances(String workflowName, String correlationId, boolean includeClosed, boolean includeTasks) {

		List<Workflow> workflows = executionDAO.getWorkflowsByCorrelationId(correlationId, includeTasks);
		List<Workflow> result = new LinkedList<>();
		for (Workflow wf : workflows) {
			if (wf.getWorkflowType().equals(workflowName) && (includeClosed || wf.getStatus().equals(Workflow.WorkflowStatus.RUNNING))) {
				result.add(wf);
			}
		}

		return result;
	}

	public Workflow getExecutionStatus(String workflowId, boolean includeTasks) {
		return executionDAO.getWorkflow(workflowId, includeTasks);
	}

	public List<String> getRunningWorkflows(String workflowName) {
		return executionDAO.getRunningWorkflowIds(workflowName);
	}

	public void removeWorkflow(String workflowId, boolean archiveWorkflow) {
		executionDAO.removeWorkflow(workflowId, archiveWorkflow);
	}

	public SearchResult<WorkflowSummary> search(String query, String freeText, int start, int size, List<String> sortOptions) {

		SearchResult<String> result = indexer.searchWorkflows(query, freeText, start, size, sortOptions);
		List<WorkflowSummary> workflows = result.getResults().stream().parallel().map(workflowId -> {
			try {
				return new WorkflowSummary(executionDAO.getWorkflow(workflowId,false));
			} catch(Exception e) {
				logger.error(e.getMessage(), e);
				return null;
			}
		}).filter(Objects::nonNull).collect(Collectors.toList());
		int missing = result.getResults().size() - workflows.size();
		long totalHits = result.getTotalHits() - missing;
		return new SearchResult<WorkflowSummary>(totalHits, workflows);
	}

	public SearchResult<WorkflowSummary> searchWorkflowByTasks(String query, String freeText, int start, int size, List<String> sortOptions) {
		SearchResult<TaskSummary> taskSummarySearchResult = searchTasks(query, freeText, start, size, sortOptions);
		List<WorkflowSummary> workflowSummaries = taskSummarySearchResult.getResults().stream()
				.parallel()
				.map(taskSummary -> {
					try {
						String workflowId = taskSummary.getWorkflowId();
						return new WorkflowSummary(executionDAO.getWorkflow(workflowId, false));
					} catch (Exception e) {
						logger.error("Error fetching workflow by id: ", e);
						return null;
					}
				})
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
		int missing = taskSummarySearchResult.getResults().size() - workflowSummaries.size();
		long totalHits = taskSummarySearchResult.getTotalHits() - missing;
		return new SearchResult<>(totalHits, workflowSummaries);
	}

	public SearchResult<TaskSummary> searchTasks(String query, String freeText, int start, int size, List<String> sortOptions) {

		SearchResult<String> result = indexer.searchTasks(query, freeText, start, size, sortOptions);
		List<TaskSummary> workflows = result.getResults().stream()
				.parallel()
				.map(taskId -> {
					try {
						return new TaskSummary(executionDAO.getTask(taskId));
					} catch(Exception e) {
						logger.error(e.getMessage(), e);
						return null;
					}
				})
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
		int missing = result.getResults().size() - workflows.size();
		long totalHits = result.getTotalHits() - missing;
		return new SearchResult<>(totalHits, workflows);
	}

	public SearchResult<TaskSummary> getSearchTasks(String query, String freeText, int start, int size, String sortString) {

	    ServiceUtils.checkArgument(size < maxSearchSize, String.format("Cannot return more than %d workflows." +
                " Please use pagination.", maxSearchSize));
	    return searchTasks(query, freeText, start, size, ServiceUtils.convertToSortedList(sortString));
    }

	public List<Task> getPendingTasksForTaskType(String taskType) {
		return executionDAO.getPendingTasksForTaskType(taskType);
	}

	public boolean addEventExecution(EventExecution eventExecution) {
		return executionDAO.addEventExecution(eventExecution);
	}

	public void removeEventExecution(EventExecution eventExecution) {
		executionDAO.removeEventExecution(eventExecution);
	}

	public void updateEventExecution(EventExecution eventExecution) {
		executionDAO.updateEventExecution(eventExecution);
	}

	/**
	 *
	 * @param queue Name of the registered queue
	 * @param msg Message
	 */
	public void addMessage(String queue, Message msg) {
		executionDAO.addMessage(queue, msg);
	}

	/**
	 * Adds task logs
	 * @param taskId Id of the task
	 * @param log logs
	 */
	public void log(String taskId, String log) {
		TaskExecLog executionLog = new TaskExecLog();
		executionLog.setTaskId(taskId);
		executionLog.setLog(log);
		executionLog.setCreatedTime(System.currentTimeMillis());
		executionDAO.addTaskExecLog(Collections.singletonList(executionLog));
	}

	/**
	 *
	 * @param taskId Id of the task for which to retrieve logs
	 * @return Execution Logs (logged by the worker)
	 */
	public List<TaskExecLog> getTaskLogs(String taskId) {
		return indexer.getTaskExecutionLogs(taskId);
	}

}
