/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal;

import static io.airbyte.workers.temporal.TemporalUtils.getTemporalClientWhenConnected;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.airbyte.commons.concurrency.VoidCallable;
import io.airbyte.workers.WorkerException;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.namespace.v1.NamespaceInfo;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TemporalUtilsTest {

  private static final String TASK_QUEUE = "default";

  @Test
  void testAsyncExecute() throws Exception {
    final CountDownLatch countDownLatch = new CountDownLatch(1);

    final VoidCallable callable = mock(VoidCallable.class);

    // force it to wait until we can verify that it is running.
    doAnswer((a) -> {
      countDownLatch.await(1, TimeUnit.MINUTES);
      return null;
    }).when(callable).call();

    final TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
    final WorkflowServiceStubs temporalService = testEnv.getWorkflowService();
    final Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestWorkflow.WorkflowImpl.class);
    final WorkflowClient client = testEnv.getWorkflowClient();
    worker.registerActivitiesImplementations(new TestWorkflow.Activity1Impl(callable));
    testEnv.start();

    final TestWorkflow workflowStub = client.newWorkflowStub(TestWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    final ImmutablePair<WorkflowExecution, CompletableFuture<String>> pair = TemporalUtils.asyncExecute(
        workflowStub,
        workflowStub::run,
        "whatever",
        String.class);

    final WorkflowExecution workflowExecution = pair.getLeft();
    final String workflowId = workflowExecution.getWorkflowId();
    final String runId = workflowExecution.getRunId();

    final WorkflowExecutionInfo workflowExecutionInfo = temporalService.blockingStub().listOpenWorkflowExecutions(null).getExecutionsList().get(0);
    assertEquals(workflowId, workflowExecutionInfo.getExecution().getWorkflowId());
    assertEquals(runId, workflowExecutionInfo.getExecution().getRunId());

    // allow the workflow to complete.
    countDownLatch.countDown();

    final String result = pair.getRight().get(1, TimeUnit.MINUTES);
    assertEquals("completed", result);
  }

  @Test
  void testWaitForTemporalServerAndLogThrowsException() {
    final WorkflowServiceStubs workflowServiceStubs = mock(WorkflowServiceStubs.class, Mockito.RETURNS_DEEP_STUBS);
    final DescribeNamespaceResponse describeNamespaceResponse = mock(DescribeNamespaceResponse.class);
    final NamespaceInfo namespaceInfo = mock(NamespaceInfo.class);
    final Supplier<WorkflowServiceStubs> serviceSupplier = mock(Supplier.class);

    when(namespaceInfo.getName()).thenReturn("default");
    when(describeNamespaceResponse.getNamespaceInfo()).thenReturn(namespaceInfo);
    when(serviceSupplier.get())
        .thenThrow(RuntimeException.class)
        .thenReturn(workflowServiceStubs);
    when(workflowServiceStubs.blockingStub().listNamespaces(any()).getNamespacesList())
        .thenThrow(RuntimeException.class)
        .thenReturn(List.of(describeNamespaceResponse));
    getTemporalClientWhenConnected(Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(0), serviceSupplier);
  }

  @Test
  void testWaitThatTimesOut() {
    final WorkflowServiceStubs workflowServiceStubs = mock(WorkflowServiceStubs.class, Mockito.RETURNS_DEEP_STUBS);
    final DescribeNamespaceResponse describeNamespaceResponse = mock(DescribeNamespaceResponse.class);
    final NamespaceInfo namespaceInfo = mock(NamespaceInfo.class);
    final Supplier<WorkflowServiceStubs> serviceSupplier = mock(Supplier.class);

    when(namespaceInfo.getName()).thenReturn("default");
    when(describeNamespaceResponse.getNamespaceInfo()).thenReturn(namespaceInfo);
    when(serviceSupplier.get())
        .thenThrow(RuntimeException.class)
        .thenReturn(workflowServiceStubs);
    when(workflowServiceStubs.blockingStub().listNamespaces(any()).getNamespacesList())
        .thenThrow(RuntimeException.class)
        .thenReturn(List.of(describeNamespaceResponse));
    assertThrows(RuntimeException.class, () -> {
      getTemporalClientWhenConnected(Duration.ofMillis(100), Duration.ofMillis(10), Duration.ofSeconds(0), serviceSupplier);
    });
  }

  @Test
  void testRuntimeExceptionOnHeartbeatWrapper() {
    final TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
    final Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestFailingWorkflow.WorkflowImpl.class);
    final WorkflowClient client = testEnv.getWorkflowClient();
    final AtomicInteger timesReachedEnd = new AtomicInteger(0);
    worker.registerActivitiesImplementations(new TestFailingWorkflow.Activity1Impl(timesReachedEnd));
    testEnv.start();

    final TestFailingWorkflow workflowStub =
        client.newWorkflowStub(TestFailingWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

    // test runtime first
    assertThrows(RuntimeException.class, () -> {
      workflowStub.run("runtime");
    });

    // we should never retry enough to reach the end
    assertEquals(0, timesReachedEnd.get());
  }

  @Test
  void testWorkerExceptionOnHeartbeatWrapper() {
    final TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
    final Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestFailingWorkflow.WorkflowImpl.class);
    final WorkflowClient client = testEnv.getWorkflowClient();
    final AtomicInteger timesReachedEnd = new AtomicInteger(0);
    worker.registerActivitiesImplementations(new TestFailingWorkflow.Activity1Impl(timesReachedEnd));
    testEnv.start();

    final TestFailingWorkflow workflowStub =
        client.newWorkflowStub(TestFailingWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

    // throws workerexception wrapped in a WorkflowFailedException
    assertThrows(WorkflowFailedException.class, () -> workflowStub.run("worker"));

    // we should never retry enough to reach the end
    assertEquals(0, timesReachedEnd.get());
  }

  @Test
  void testHeartbeatWithContext() throws InterruptedException {

    final TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();

    final Worker worker = testEnv.newWorker(TASK_QUEUE);

    worker.registerWorkflowImplementationTypes(HeartbeatWorkflow.HeartbeatWorkflowImpl.class);
    final WorkflowClient client = testEnv.getWorkflowClient();

    final CountDownLatch latch = new CountDownLatch(2);

    worker.registerActivitiesImplementations(new HeartbeatWorkflow.HeartbeatActivityImpl(() -> {
      ActivityExecutionContext context = Activity.getExecutionContext();
      TemporalUtils.withBackgroundHeartbeat(
          // TODO (itaseski) figure out how to decrease heartbeat intervals using reflection
          () -> {
            latch.await();
            return new Object();
          },
          () -> {
            latch.countDown();
            return context;
          });
    }));

    testEnv.start();

    final HeartbeatWorkflow heartbeatWorkflow = client.newWorkflowStub(
        HeartbeatWorkflow.class,
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .build());

    // use async execution to avoid blocking the test thread
    WorkflowClient.start(heartbeatWorkflow::execute);

    assertTrue(latch.await(15, TimeUnit.SECONDS));

  }

  @Test
  void testHeartbeatWithContextAndCallbackRef() throws InterruptedException {

    final TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();

    final Worker worker = testEnv.newWorker(TASK_QUEUE);

    worker.registerWorkflowImplementationTypes(HeartbeatWorkflow.HeartbeatWorkflowImpl.class);
    final WorkflowClient client = testEnv.getWorkflowClient();

    final CountDownLatch latch = new CountDownLatch(2);

    worker.registerActivitiesImplementations(new HeartbeatWorkflow.HeartbeatActivityImpl(() -> {
      ActivityExecutionContext context = Activity.getExecutionContext();
      TemporalUtils.withBackgroundHeartbeat(
          // TODO (itaseski) figure out how to decrease heartbeat intervals using reflection
          new AtomicReference<>(() -> {}),
          () -> {
            latch.await();
            return new Object();
          },
          () -> {
            latch.countDown();
            return context;
          });
    }));

    testEnv.start();

    final HeartbeatWorkflow heartbeatWorkflow = client.newWorkflowStub(
        HeartbeatWorkflow.class,
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .build());

    // use async execution to avoid blocking the test thread
    WorkflowClient.start(heartbeatWorkflow::execute);

    assertTrue(latch.await(15, TimeUnit.SECONDS));

  }

  @WorkflowInterface
  public interface TestWorkflow {

    @WorkflowMethod
    String run(String arg);

    class WorkflowImpl implements TestWorkflow {

      private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowImpl.class);

      private final ActivityOptions options = ActivityOptions.newBuilder()
          .setScheduleToCloseTimeout(Duration.ofDays(3))
          .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
          .setRetryOptions(TemporalUtils.NO_RETRY)
          .build();

      private final Activity1 activity1 = Workflow.newActivityStub(Activity1.class, options);
      private final Activity1 activity2 = Workflow.newActivityStub(Activity1.class, options);

      @Override
      public String run(final String arg) {
        LOGGER.info("workflow before activity 1");
        activity1.activity();
        LOGGER.info("workflow before activity 2");
        activity2.activity();
        LOGGER.info("workflow after all activities");

        return "completed";
      }

    }

    @ActivityInterface
    interface Activity1 {

      @ActivityMethod
      void activity();

    }

    class Activity1Impl implements Activity1 {

      private static final Logger LOGGER = LoggerFactory.getLogger(TestWorkflow.Activity1Impl.class);
      private static final String ACTIVITY1 = "activity1";

      private final VoidCallable callable;

      public Activity1Impl(final VoidCallable callable) {
        this.callable = callable;
      }

      public void activity() {
        LOGGER.info("before: {}", ACTIVITY1);
        try {
          callable.call();
        } catch (final Exception e) {
          throw new RuntimeException(e);
        }
        LOGGER.info("before: {}", ACTIVITY1);
      }

    }

  }

  @WorkflowInterface
  public interface TestFailingWorkflow {

    @WorkflowMethod
    String run(String arg);

    class WorkflowImpl implements TestFailingWorkflow {

      private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowImpl.class);

      final ActivityOptions options = ActivityOptions.newBuilder()
          .setScheduleToCloseTimeout(Duration.ofMinutes(30))
          .setStartToCloseTimeout(Duration.ofMinutes(30))
          .setScheduleToStartTimeout(Duration.ofMinutes(30))
          .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
          .setRetryOptions(TemporalUtils.NO_RETRY)
          .setHeartbeatTimeout(Duration.ofSeconds(1))
          .build();

      private final Activity1 activity1 = Workflow.newActivityStub(Activity1.class, options);

      @Override
      public String run(final String arg) {

        LOGGER.info("workflow before activity 1");
        activity1.activity(arg);
        LOGGER.info("workflow after all activities");

        return "completed";
      }

    }

    @ActivityInterface
    interface Activity1 {

      @ActivityMethod
      void activity(String arg);

    }

    class Activity1Impl implements Activity1 {

      private static final Logger LOGGER = LoggerFactory.getLogger(TestWorkflow.Activity1Impl.class);
      private static final String ACTIVITY1 = "activity1";

      private final AtomicInteger timesReachedEnd;

      public Activity1Impl(final AtomicInteger timesReachedEnd) {
        this.timesReachedEnd = timesReachedEnd;
      }

      public void activity(String arg) {
        LOGGER.info("before: {}", ACTIVITY1);
        ActivityExecutionContext context = Activity.getExecutionContext();
        TemporalUtils.withBackgroundHeartbeat(
            new AtomicReference<>(null),
            () -> {
              if (timesReachedEnd.get() == 0) {
                if (arg.equals("runtime")) {
                  throw new RuntimeException("failed");
                } else if (arg.equals("timeout")) {
                  Thread.sleep(10000);
                  return null;
                } else {
                  throw new WorkerException("failed");
                }
              } else {
                return null;
              }
            },
            () -> context);
        timesReachedEnd.incrementAndGet();
        LOGGER.info("before: {}", ACTIVITY1);
      }

    }

  }

}
