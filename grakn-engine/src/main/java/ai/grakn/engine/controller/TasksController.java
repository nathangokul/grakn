/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.engine.controller;


import static ai.grakn.engine.controller.util.Requests.mandatoryQueryParameter;
import static ai.grakn.engine.tasks.TaskSchedule.recurring;
import static ai.grakn.util.REST.WebPath.Tasks.GET;
import static ai.grakn.util.REST.WebPath.Tasks.STOP;
import static ai.grakn.util.REST.WebPath.Tasks.TASKS;
import static java.lang.Long.parseLong;
import static java.time.Instant.ofEpochMilli;
import static java.util.stream.Collectors.toList;

import ai.grakn.engine.TaskId;
import ai.grakn.engine.TaskStatus;
import ai.grakn.engine.tasks.BackgroundTask;
import ai.grakn.engine.tasks.TaskConfiguration;
import ai.grakn.engine.tasks.TaskManager;
import ai.grakn.engine.tasks.TaskSchedule;
import ai.grakn.engine.tasks.TaskState;
import ai.grakn.exception.GraknBackendException;
import ai.grakn.exception.GraknServerException;
import ai.grakn.util.REST;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import mjson.Json;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;

/**
 * <p>
 * Endpoints used to query and control queued background tasks.
 * </p>
 *
 * @author Denis Lobanov, alexandraorth
 */
@Path("/tasks")
@Api(value = "/tasks", description = "Endpoints used to query and control queued background tasks.", produces = "application/json")
public class TasksController {

    private static final Logger LOG = LoggerFactory.getLogger(TasksController.class);
    private static final TaskState.Priority DEFAULT_TASK_PRIORITY = TaskState.Priority.LOW;

    private static final int MAX_THREADS = 10;
    private static final Duration MAX_EXECUTION_TIME = Duration.ofSeconds(10);

    private final TaskManager manager;
    private final ExecutorService executor;

    public TasksController(Service spark, TaskManager manager) {
        if (manager==null) {
            throw GraknServerException.internalError("Task manager has not been instantiated.");
        }
        this.manager = manager;

        spark.get(TASKS, this::getTasks);
        spark.get(GET, this::getTask);
        spark.put(STOP, this::stopTask);
        spark.post(TASKS, this::createTasks);

        spark.exception(GraknServerException.class, (e, req, res) -> handleNotFoundInStorage(e, res));
        spark.exception(GraknBackendException.class, (e, req, res) -> handleNotFoundInStorage(e, res));
        this.executor = Executors.newFixedThreadPool(MAX_THREADS);
    }

    @GET
    @Path("/")
    @ApiOperation(value = "Get tasks matching a specific TaskStatus.")
    @ApiImplicitParams({
            @ApiImplicitParam(name = REST.Request.TASK_STATUS_PARAMETER, value = "TaskStatus as string.", dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = REST.Request.TASK_CLASS_NAME_PARAMETER, value = "Class name of BackgroundTask Object.", dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = REST.Request.TASK_CREATOR_PARAMETER, value = "Who instantiated these tasks.", dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = REST.Request.LIMIT_PARAM, value = "Limit the number of entries in the returned result.", dataType = "integer", paramType = "query"),
            @ApiImplicitParam(name = REST.Request.OFFSET_PARAM, value = "Use in conjunction with limit for pagination.", dataType = "integer", paramType = "query")
    })
    private Json getTasks(Request request, Response response) {
        TaskStatus status = null;
        String className = request.queryParams(REST.Request.TASK_CLASS_NAME_PARAMETER);
        String creator = request.queryParams(REST.Request.TASK_CREATOR_PARAMETER);
        int limit = 0;
        int offset = 0;

        if (request.queryParams(REST.Request.LIMIT_PARAM) != null) {
            limit = Integer.parseInt(request.queryParams(REST.Request.LIMIT_PARAM));
        }

        if (request.queryParams(REST.Request.OFFSET_PARAM) != null) {
            offset = Integer.parseInt(request.queryParams(REST.Request.OFFSET_PARAM));
        }

        if (request.queryParams(REST.Request.TASK_STATUS_PARAMETER) != null) {
            status = TaskStatus.valueOf(request.queryParams(REST.Request.TASK_STATUS_PARAMETER));
        }

        Json result = Json.array();
        manager.storage()
                .getTasks(status, className, creator, null, limit, offset).stream()
                .map(this::serialiseStateSubset)
                .forEach(result::add);

        response.status(200);
        response.type(ContentType.APPLICATION_JSON.getMimeType());

        return result;
    }

    @GET
    @Path("/{id}")
    @ApiOperation(value = "Get the state of a specific task by its ID.", produces = "application/json")
    @ApiImplicitParam(name = REST.Request.UUID_PARAMETER, value = "ID of task.", required = true, dataType = "string", paramType = "path")
    private Json getTask(Request request, Response response) {
        String id = request.params("id");

        response.status(200);
        response.type("application/json");

        return serialiseStateFull(manager.storage().getState(TaskId.of(id)));
    }

    @PUT
    @Path("/{id}/stop")
    @ApiOperation(value = "Stop a running or paused task.")
    @ApiImplicitParam(name = REST.Request.UUID_PARAMETER, value = "ID of task.", required = true, dataType = "string", paramType = "path")
    private Json stopTask(Request request, Response response) {
        String id = request.params(REST.Request.ID_PARAMETER);
        manager.stopTask(TaskId.of(id));
        return Json.object();
    }

    @POST
    @Path("/")
    @ApiOperation(value = "Schedule a set of tasks.")
    @ApiImplicitParams({
            @ApiImplicitParam(name = REST.Request.TASKS_PARAM, value = "JSON Array containing an ordered list of task parameters and comfigurations.", required = true, dataType = "List", paramType = "body")
    })
    private Json createTasks(Request request, Response response) {
        Json requestBodyAsJson = bodyAsJson(request);
        // This covers the previous behaviour. It looks like a quirk of the testing
        // client library we are using. Consider deprecating it.
        if (requestBodyAsJson.has("value")) {
            requestBodyAsJson = requestBodyAsJson.at("value");
        }
        if (!requestBodyAsJson.has(REST.Request.TASKS_PARAM)) {
            LOG.error("Malformed request body: {}", requestBodyAsJson);
            throw GraknServerException.requestMissingBodyParameters(REST.Request.TASKS_PARAM);
        }
        List<Json> taskJsonList = requestBodyAsJson.at(REST.Request.TASKS_PARAM).asJsonList();
        Json responseJson = Json.array();
        response.type(ContentType.APPLICATION_JSON.getMimeType());
        // We need to return the list of taskStates in order
        // so the client can relate the state to each element in the request.
        List<TaskStateWithConfiguration> taskStates = new ArrayList<>();
        for (int i = 0; i < taskJsonList.size(); i++) {
            Json singleTaskJson = taskJsonList.get(i);
            try {
                taskStates.add(new TaskStateWithConfiguration(
                                extractParametersAndProcessTask(singleTaskJson),
                                extractConfiguration(singleTaskJson), i));
            } catch (Exception e) {
                LOG.error("Malformed request at {}", singleTaskJson, e);
                // We return a failure for the full request as this imply there is
                // something wrong in the client logic that needs to be addressed
                throw e;
            }
        }

        List<CompletableFuture<Json>> futures = taskStates.stream()
                .map(taskStateWithConfiguration -> CompletableFuture
                        .supplyAsync(() -> addTaskToManager(taskStateWithConfiguration), executor))
                .collect(toList());
        CompletableFuture<List<Json>> completableFuture = all(futures);
        try {
            List<Json> results = completableFuture
                    .get(MAX_EXECUTION_TIME.getSeconds(), TimeUnit.SECONDS);
            boolean hasFailures = false;
            for (Json resultForTask : results) {
                responseJson.add(resultForTask);
                if (resultForTask.at("code").asInteger() != HttpStatus.SC_OK) {
                    hasFailures = true;
                }
            }
            if (!hasFailures) {
                response.status(HttpStatus.SC_OK);
            } else if (responseJson.asJsonList().size() > 0) {
                response.status(HttpStatus.SC_ACCEPTED);
            } else {
                response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR);
            }
            return responseJson;
        } catch (TimeoutException | InterruptedException e) {
            LOG.error("Task interrupted", e);
            response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR);
            return Json.object();
        } catch (Exception e) {
            LOG.error("Exception while processing batch of tasks", e);
            response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR);
            return Json.object();
        }
    }

    private Json extractConfiguration(Json taskJson) {
        if (taskJson.has(REST.Request.CONFIGURATION_PARAM)) {
            Json config = taskJson.at(REST.Request.CONFIGURATION_PARAM);
            if (config.isNull()) {
                return Json.nil();
            }
            if (!config.isObject()) {
                throw GraknServerException.requestMissingParameters(REST.Request.CONFIGURATION_PARAM);
            } else {
                return config;
            }
        } else {
            return Json.object();
        }
    }

    private Json addTaskToManager(TaskStateWithConfiguration taskState) {
        Json singleTaskReturnJson = Json.object().set("index", taskState.getIndex());
        try {
            manager.addTask(taskState.getTaskState(), TaskConfiguration.of(taskState.getConfiguration()));
            singleTaskReturnJson.set("id", taskState.getTaskState().getId().getValue());
            singleTaskReturnJson.set("code", HttpStatus.SC_OK);
        } catch (Exception e) {
            LOG.error("Server error while adding the task", e);
            singleTaskReturnJson.set("code", HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
        return singleTaskReturnJson;
    }

    static private <T> CompletableFuture<List<T>> all(List<CompletableFuture<T>> cf) {
        return CompletableFuture.allOf(cf.toArray(new CompletableFuture[cf.size()]))
                .thenApply(v -> cf.stream()
                        .map(CompletableFuture::join)
                        .collect(toList())
                );
    }

    private TaskState extractParametersAndProcessTask(Json singleTaskJson) {
        Function<String, Optional<Json>> extractor = p -> Optional
                .ofNullable(singleTaskJson.at(p));
        String className = mandatoryQueryParameter(extractor,
                REST.Request.TASK_CLASS_NAME_PARAMETER).asString();
        String createdBy = mandatoryQueryParameter(extractor,
                REST.Request.TASK_CREATOR_PARAMETER).asString();
        String runAtTime = mandatoryQueryParameter(extractor,
                REST.Request.TASK_RUN_AT_PARAMETER).asString();
        String intervalParam = extractor.apply(REST.Request.TASK_RUN_INTERVAL_PARAMETER)
                .map(Json::asString).orElse(null);
        String priorityParam = extractor.apply(REST.Request.TASK_PRIORITY_PARAMETER)
                .map(Json::asString).orElse(null);
        return processTask(className, createdBy, runAtTime, intervalParam, priorityParam);
    }

    private TaskState processTask(String className, String createdBy, String runAtTime,
            String intervalParam, String priorityParam) {
        TaskSchedule schedule;
        TaskState.Priority priority;
        try {
            // Get the schedule of the task
            Optional<Duration> optionalInterval = Optional.ofNullable(intervalParam)
                    .map(Long::valueOf).map(Duration::ofMillis);
            Instant time = ofEpochMilli(parseLong(runAtTime));
            schedule = optionalInterval
                    .map(interval -> recurring(time, interval))
                    .orElse(TaskSchedule.at(time));

            // Get the priority of a task (default is low)
            priority = Optional.ofNullable(priorityParam).map(TaskState.Priority::valueOf)
                    .orElse(DEFAULT_TASK_PRIORITY);
        } catch (Exception e) {
            throw GraknServerException.serverException(400, e);
        }

        // Get the class of this background task
        Class<?> clazz = getClass(className);
        return TaskState.of(clazz, createdBy, schedule, priority);
    }

    private Json bodyAsJson(Request request) {
        String requestBody = request.body();
        if (requestBody.isEmpty()) {
            return Json.object();
        }
        try {
            return Json.read(requestBody);
        } catch (Exception e) {
            LOG.error("Malformed json in body of request {}", requestBody);
            throw GraknServerException.serverException(400, e);
        }
    }

    /**
     * Use reflection to get a reference to the given class. Returns a 500 to the client if the
     * class is unavailable.
     *
     * @param className class to retrieve
     * @return reference to the given class
     */
    private Class<?> getClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            if (!BackgroundTask.class.isAssignableFrom(clazz)) {
                throw GraknServerException.invalidTask(className);
            }

            return clazz;
        } catch (ClassNotFoundException e) {
            
            throw GraknServerException.invalidTask(className);

        }
    }

    /**
     * Error accessing or retrieving a task from storage. This throws a 404 Task Not Found to the user.
     * @param exception {@link GraknServerException} thrown by the server
     * @param response The response object providing functionality for modifying the response
     */
    private void handleNotFoundInStorage(Exception exception, Response response){
        //TODO: Fix this. This is needed because of a mixture of exceptions being thrown within the context of this controller
        if(exception instanceof GraknServerException){
            response.status(((GraknServerException) exception).getStatus());
            response.body(Json.object("exception", exception.getMessage()).toString());
        } else {
            response.status(404);
        }
    }

    // TODO: Return 'schedule' object as its own object
    private Json serialiseStateSubset(TaskState state) {
        return Json.object()
                .set("id", state.getId().getValue())
                .set("status", state.status().name())
                .set("creator", state.creator())
                .set("className", state.taskClass().getName())
                .set("runAt", state.schedule().runAt().toEpochMilli())
                .set("recurring", state.schedule().isRecurring());
    }

    private Json serialiseStateFull(TaskState state) {
        return serialiseStateSubset(state)
                .set("interval", state.schedule().interval().map(Duration::toMillis).orElse(null))
                .set("recurring", state.schedule().isRecurring())
                .set("exception", state.exception())
                .set("stackTrace", state.stackTrace())
                .set("engineID", state.engineID() != null ? state.engineID().value() : null);
    }

    private static class TaskStateWithConfiguration {

        private final TaskState taskState;
        private Json configuration;
        private final int index;

        TaskStateWithConfiguration(TaskState taskState, Json configuration, int index) {
            this.taskState = taskState;
            this.configuration = configuration;
            this.index = index;
        }

        public TaskState getTaskState() {
            return taskState;
        }

        public int getIndex() {
            return index;
        }

        public Json getConfiguration() {
            return configuration;
        }
    }
}
