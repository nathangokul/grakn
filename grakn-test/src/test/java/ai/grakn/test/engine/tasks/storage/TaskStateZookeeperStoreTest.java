/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Ltd
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

package ai.grakn.test.engine.tasks.storage;

import ai.grakn.engine.TaskId;
import ai.grakn.engine.tasks.TaskCheckpoint;
import ai.grakn.engine.tasks.TaskSchedule;
import ai.grakn.engine.tasks.TaskState;
import ai.grakn.engine.tasks.TaskStateStorage;
import ai.grakn.engine.tasks.connection.ZookeeperConnection;
import ai.grakn.engine.tasks.mock.ShortExecutionMockTask;
import ai.grakn.engine.tasks.storage.TaskStateZookeeperStore;
import ai.grakn.engine.util.EngineID;
import ai.grakn.test.EngineContext;
import mjson.Json;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static ai.grakn.engine.TaskStatus.CREATED;
import static ai.grakn.engine.TaskStatus.RUNNING;
import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TaskStateZookeeperStoreTest {
    private static ZookeeperConnection connection;
    private static TaskStateStorage stateStorage;

    @ClassRule
    public static final EngineContext engine = EngineContext.startKafkaServer();

    @BeforeClass
    public static void setUp() throws Exception {
        connection = new ZookeeperConnection(engine.config());
        stateStorage = new TaskStateZookeeperStore(connection);
    }

    @AfterClass
    public static void closeZkConnection(){
       connection.close();
    }

    @Test
    public void testStoreRetrieve() throws Exception {
        TaskId id = stateStorage.newState(task());

        // Retrieve
        TaskState state = stateStorage.getState(id);
        assertNotNull(state);
        assertEquals(CREATED, state.status());
        assertEquals(ShortExecutionMockTask.class, state.taskClass());
        assertEquals(this.getClass().getName(), state.creator());
    }

    @Test
    public void whenUpdatingTask_StateChanges() throws Exception {
        TaskId id = stateStorage.newState(task());
        EngineID me = EngineID.me();

        // Change
        TaskCheckpoint checkpoint = TaskCheckpoint.of(Json.object("checkpoint", true));
        TaskState state = stateStorage.getState(id)
                .markRunning(me)
                .checkpoint(checkpoint);

        // Update
        stateStorage.updateState(state);

        // Retrieve the task and check properties correct
        state = stateStorage.getState(id);
        assertEquals(RUNNING, state.status());
        assertEquals(me, state.engineID());
        assertEquals(checkpoint, state.checkpoint());
    }

    @Test
    public void testUpdateInvalid() throws Exception {
        TaskId id = stateStorage.newState(task());

        // update
        EngineID engineID = EngineID.of(UUID.randomUUID().toString());
        TaskCheckpoint checkpoint = TaskCheckpoint.of(Json.object("checkpoint", true));

        TaskState state = stateStorage.getState(id);
        stateStorage.updateState(state.markRunning(engineID).checkpoint(checkpoint));

        // get again
        state = stateStorage.getState(id);
        assertEquals(RUNNING, state.status());
        assertEquals(engineID, state.engineID());
        assertEquals(checkpoint, state.checkpoint());
    }


    @Test
    public void testGetTasksByStatus() {
        TaskId id = stateStorage.newState(task());
        stateStorage.newState(task().markScheduled());
        Set<TaskState> res = stateStorage.getTasks(CREATED, null, null, null, 0, 0);

        assertTrue(res.parallelStream()
                .map(TaskState::getId)
                .filter(x -> x.equals(id))
                .collect(Collectors.toList())
                .size() == 1);
    }

    @Test
    public void testGetTasksByCreator() {
        TaskId id = stateStorage.newState(task());
        stateStorage.newState(task("another"));
        Set<TaskState> res = stateStorage.getTasks(null, null, this.getClass().getName(), null, 0, 0);

        assertTrue(res.parallelStream()
                .map(TaskState::getId)
                .filter(x -> x.equals(id))
                .collect(Collectors.toList())
                .size() == 1);
    }

    @Test
    public void testGetTasksByClassName() {
        TaskId id = stateStorage.newState(task());
        Set<TaskState> res = stateStorage.getTasks(null, ShortExecutionMockTask.class.getName(), null, null, 0, 0);

        assertTrue(res.parallelStream()
                .map(TaskState::getId)
                .filter(x -> x.equals(id))
                .collect(Collectors.toList())
                .size() == 1);
    }

    @Test
    public void testGetAllTasks() {
        TaskId id = stateStorage.newState(task());
        Set<TaskState> res = stateStorage.getTasks(null, null, null, null, 0, 0);

        assertTrue(res.parallelStream()
                .map(TaskState::getId)
                .filter(x -> x.equals(id))
                .collect(Collectors.toList())
                .size() == 1);
    }

    @Test
    public void testGetByRunningEngine(){
        EngineID me = EngineID.me();
        TaskId id = stateStorage.newState(task().markRunning(me));

        Set<TaskState> res = stateStorage.getTasks(null, null, null, me, 1, 0);
        TaskState resultant = res.iterator().next();
        Assert.assertEquals(resultant.getId(), id);
        Assert.assertEquals(resultant.engineID(), me);
    }

    @Test
    public void testTaskPagination() {
        for (int i = 0; i < 20; i++) {
            stateStorage.newState(task());
        }

        Set<TaskState> setA = stateStorage.getTasks(null, null, null, null, 10, 0);
        Set<TaskState> setB = stateStorage.getTasks(null, null, null, null, 10, 10);

        setA.forEach(x -> assertFalse(setB.contains(x)));
    }

    public TaskState task(){
        return task(this.getClass().getName());
    }

    public TaskState task(String creator){
        return TaskState.of(ShortExecutionMockTask.class, creator, TaskSchedule.now(), TaskState.Priority.LOW);
    }

    /**
     * Returns true when the given path exists in zookeeper
     */
    private boolean pathExists(String path) throws Exception {
        return connection.connection().checkExists().forPath(path) != null;
    }
}
