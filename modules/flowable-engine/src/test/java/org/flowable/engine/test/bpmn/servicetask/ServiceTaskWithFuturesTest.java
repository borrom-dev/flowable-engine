/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.engine.test.bpmn.servicetask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.async.AsyncTaskExecutor;
import org.flowable.common.engine.impl.history.HistoryLevel;
import org.flowable.common.engine.impl.javax.el.ELException;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.FlowableFutureJavaDelegate;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.delegate.MapBasedFlowableFutureJavaDelegate;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.impl.test.HistoryTestHelper;
import org.flowable.engine.impl.test.PluggableFlowableTestCase;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.test.Deployment;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.junit.jupiter.api.Test;

/**
 * @author Filip Hrisafov
 */
class ServiceTaskWithFuturesTest extends PluggableFlowableTestCase {

    @Test
    @Deployment
    void testExpressionReturnsFuture() {

        CountDownLatch latch = new CountDownLatch(2);
        TestBeanReturnsFuture testBean = new TestBeanReturnsFuture(latch, processEngineConfiguration.getAsyncTaskExecutor());
        ProcessInstance processInstance = runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey("myProcess")
                .transientVariable("bean", testBean)
                .start();

        assertProcessEnded(processInstance.getId());

        if (HistoryTestHelper.isHistoryLevelAtLeast(HistoryLevel.AUDIT, processEngineConfiguration)) {
            HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstance.getId())
                    .singleResult();

            // Every service task sleeps for 1s, but when we use futures it should take less then 2s.
            assertThat(historicProcessInstance.getDurationInMillis()).isLessThan(1500);
        }
    }

    @Test
    @Deployment(resources = "org/flowable/engine/test/bpmn/servicetask/ServiceTaskWithFuturesTest.testExpressionReturnsFuture.bpmn20.xml")
    void testExpressionDoesNotReturnFuture() {

        assertThatThrownBy(() -> {
            CountDownLatch latch = new CountDownLatch(2);
            TestBean testBean = new TestBean(latch);
            ProcessInstance processInstance = runtimeService.createProcessInstanceBuilder()
                    .processDefinitionKey("myProcess")
                    .transientVariable("bean", testBean)
                    .start();
        })
                .isExactlyInstanceOf(FlowableException.class)
                .getCause()
                .isInstanceOf(ELException.class)
                .getCause()
                .isExactlyInstanceOf(FlowableException.class)
                .hasMessage("Countdown latch did not reach 0");
    }

    @Test
    @Deployment
    void testDelegateExpressionWithFutureJavaDelegate() {

        String currentThreadName = Thread.currentThread().getName();

        // When using a normal JavaDelegate this process cannot complete
        // because the latch needs to be decreased twice before every task returns a result
        // Try changing TestFutureJavaDelegate to implement JavaDelegate instead
        CountDownLatch latch = new CountDownLatch(2);
        TestFutureJavaDelegate testBean = new TestFutureJavaDelegate(latch);
        ProcessInstance processInstance = runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey("myProcess")
                .transientVariable("bean", testBean)
                .transientVariable("counter", new AtomicInteger(0))
                .start();

        assertProcessEnded(processInstance.getId());

        if (HistoryTestHelper.isHistoryLevelAtLeast(HistoryLevel.AUDIT, processEngineConfiguration)) {
            List<HistoricVariableInstance> historicVariableInstances = historyService.createHistoricVariableInstanceQuery().list();
            Map<String, Object> historicVariables = historicVariableInstances.stream()
                    .filter(variable -> !variable.getVariableName().equals("initiator"))
                    .collect(Collectors.toMap(HistoricVariableInstance::getVariableName, HistoricVariableInstance::getValue));

            assertThat(historicVariables)
                    .containsOnlyKeys(
                            "counter1", "beforeExecutionThreadName1", "executionThreadName1", "afterExecutionThreadName1",
                            "counter2", "beforeExecutionThreadName2", "executionThreadName2", "afterExecutionThreadName2"
                    )
                    .contains(
                            entry("counter1", 1),
                            entry("beforeExecutionThreadName1", currentThreadName),
                            entry("afterExecutionThreadName1", currentThreadName),
                            entry("counter2", 2),
                            entry("beforeExecutionThreadName2", currentThreadName),
                            entry("afterExecutionThreadName2", currentThreadName)
                    );

            assertThat(historicVariables.get("executionThreadName1"))
                    .asInstanceOf(STRING)
                    .isNotEqualTo(currentThreadName)
                    .startsWith("flowable-async-job-executor-thread-");

            assertThat(historicVariables.get("executionThreadName2"))
                    .asInstanceOf(STRING)
                    .isNotEqualTo(currentThreadName)
                    // The executions should be done on different threads
                    .isNotEqualTo(historicVariables.get("executionThreadName1"))
                    .startsWith("flowable-async-job-executor-thread-");
        }
    }

    @Test
    @Deployment(resources = "org/flowable/engine/test/bpmn/servicetask/ServiceTaskWithFuturesTest.testDelegateExpressionWithFutureJavaDelegate.bpmn20.xml")
    void testDelegateExpressionWithJavaDelegate() {

        // This is using a normal JavaDelegate and thus the process cannot complete
        // because the latch needs to be decreased twice before every task returns a result
        CountDownLatch latch = new CountDownLatch(2);
        TestJavaDelegate testBean = new TestJavaDelegate(latch);
        assertThatThrownBy(() -> {
            runtimeService.createProcessInstanceBuilder()
                    .processDefinitionKey("myProcess")
                    .transientVariable("bean", testBean)
                    .start();
        })
                .isExactlyInstanceOf(FlowableException.class)
                .hasNoCause()
                .hasMessage("Countdown latch did not reach 0");

    }

    @Test
    @Deployment
    void testDelegateExpressionParallelAndSequentialFutureJavaDelegates() {
        // the setup of the test is the following:
        // there are 3 delegate executions:
        // delegate1_1 -> delegate1_2
        // delegate2_1
        // for delegate 1_1 to complete delegate2_1 should start executing
        // for delegate 1_2 to complete delegate2_1 should start executing and 1_1 should be done
        // for delegate2_1 to complete delegate1_2 should complete

        CountDownLatch delegate1_1Done = new CountDownLatch(1);
        CountDownLatch delegate1_2Done = new CountDownLatch(1);
        CountDownLatch delegate2_1Done = new CountDownLatch(1);
        CountDownLatch delegate2_1Start = new CountDownLatch(1);

        MapBasedFlowableFutureJavaDelegate futureDelegate1_1 = new MapBasedFlowableFutureJavaDelegate() {

            @Override
            public Map<String, Object> execute(Map<String, Object> inputData) {

                try {

                    if (delegate2_1Start.await(2, TimeUnit.SECONDS)) {
                        AtomicInteger counter = (AtomicInteger) inputData.get("counter");
                        return Collections.singletonMap("counterDelegate1_1", counter.incrementAndGet());
                    }

                    throw new FlowableException("Delegate 2_1 did not start");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FlowableException("Thread was interrupted");
                }
            }

            @Override
            public void afterExecution(DelegateExecution execution, Map<String, Object> executionData) {
                MapBasedFlowableFutureJavaDelegate.super.afterExecution(execution, executionData);
                delegate1_1Done.countDown();
            }
        };

        MapBasedFlowableFutureJavaDelegate futureDelegate1_2 = new MapBasedFlowableFutureJavaDelegate() {

            @Override
            public Map<String, Object> execute(Map<String, Object> inputData) {
                assertThat(inputData)
                        .contains(
                                entry("counterDelegate1_1", 1)
                        )
                        .doesNotContainKeys("counterDelegate1_2", "counterDelegate2_1");
                AtomicInteger counter = (AtomicInteger) inputData.get("counter");
                return Collections.singletonMap("counterDelegate1_2", counter.incrementAndGet());
            }

            @Override
            public void afterExecution(DelegateExecution execution, Map<String, Object> executionData) {
                MapBasedFlowableFutureJavaDelegate.super.afterExecution(execution, executionData);
                delegate1_2Done.countDown();
            }
        };

        MapBasedFlowableFutureJavaDelegate futureDelegate2_1 = new MapBasedFlowableFutureJavaDelegate() {

            @Override
            public Map<String, Object> execute(Map<String, Object> inputData) {
                delegate2_1Start.countDown();
                
                try {
                    if (delegate1_2Done.await(2, TimeUnit.SECONDS)) {
                        AtomicInteger counter = (AtomicInteger) inputData.get("counter");
                        return Collections.singletonMap("counterDelegate2_1", counter.incrementAndGet());
                    }

                    throw new FlowableException("Delegate 1_2 did not complete");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FlowableException("Thread was interrupted");
                }
            }

            @Override
            public void afterExecution(DelegateExecution execution, Map<String, Object> executionData) {
                assertThat(execution.getVariables())
                        .contains(
                                entry("counterDelegate1_1", 1),
                                entry("counterDelegate1_2", 2)
                        )
                        .doesNotContainKeys("counterDelegate2_1");
                MapBasedFlowableFutureJavaDelegate.super.afterExecution(execution, executionData);
                delegate2_1Done.countDown();
            }
        };
        
        ProcessInstance processInstance = runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey("myProcess")
                .transientVariable("futureDelegate1_1", futureDelegate1_1)
                .transientVariable("futureDelegate1_2", futureDelegate1_2)
                .transientVariable("futureDelegate2_1", futureDelegate2_1)
                .transientVariable("counter", new AtomicInteger(0))
                .start();

        assertProcessEnded(processInstance.getId());

        if (HistoryTestHelper.isHistoryLevelAtLeast(HistoryLevel.AUDIT, processEngineConfiguration)) {
            List<HistoricVariableInstance> historicVariableInstances = historyService.createHistoricVariableInstanceQuery().list();
            Map<String, Object> historicVariables = historicVariableInstances.stream()
                    .filter(variable -> !variable.getVariableName().equals("initiator"))
                    .collect(Collectors.toMap(HistoricVariableInstance::getVariableName, HistoricVariableInstance::getValue));

            assertThat(historicVariables)
                    .containsOnly(
                            entry("counterDelegate1_1", 1),
                            entry("counterDelegate1_2", 2),
                            entry("counterDelegate2_1", 3)
                    );
        }
    }

    @Test
    @Deployment
    void testClassWithFutureJavaDelegate() {

        String currentThreadName = Thread.currentThread().getName();

        // When using a normal JavaDelegate this process cannot complete
        // because the latch needs to be decreased twice before every task returns a result
        // Try changing TestFutureJavaDelegate to implement JavaDelegate instead
        ProcessInstance processInstance = runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey("myProcess")
                .transientVariable("counter", new AtomicInteger(0))
                .transientVariable("countDownLatch", new CountDownLatch(2))
                .start();

        assertProcessEnded(processInstance.getId());

        if (HistoryTestHelper.isHistoryLevelAtLeast(HistoryLevel.AUDIT, processEngineConfiguration)) {
            List<HistoricVariableInstance> historicVariableInstances = historyService.createHistoricVariableInstanceQuery().list();
            Map<String, Object> historicVariables = historicVariableInstances.stream()
                    .filter(variable -> !variable.getVariableName().equals("initiator"))
                    .collect(Collectors.toMap(HistoricVariableInstance::getVariableName, HistoricVariableInstance::getValue));

            assertThat(historicVariables)
                    .containsOnlyKeys(
                            "counter1", "beforeExecutionThreadName1", "executionThreadName1", "afterExecutionThreadName1",
                            "counter2", "beforeExecutionThreadName2", "executionThreadName2", "afterExecutionThreadName2"
                    )
                    .contains(
                            entry("counter1", 1),
                            entry("beforeExecutionThreadName1", currentThreadName),
                            entry("afterExecutionThreadName1", currentThreadName),
                            entry("counter2", 2),
                            entry("beforeExecutionThreadName2", currentThreadName),
                            entry("afterExecutionThreadName2", currentThreadName)
                    );

            assertThat(historicVariables.get("executionThreadName1"))
                    .asInstanceOf(STRING)
                    .isNotEqualTo(currentThreadName)
                    .startsWith("flowable-async-job-executor-thread-");

            assertThat(historicVariables.get("executionThreadName2"))
                    .asInstanceOf(STRING)
                    .isNotEqualTo(currentThreadName)
                    // The executions should be done on different threads
                    .isNotEqualTo(historicVariables.get("executionThreadName1"))
                    .startsWith("flowable-async-job-executor-thread-");
        }
    }

    @Test
    @Deployment
    void testClassWithJavaDelegate() {

        // This is using a normal JavaDelegate and thus the process cannot complete
        // because the latch needs to be decreased twice before every task returns a result
        assertThatThrownBy(() -> {
            runtimeService.createProcessInstanceBuilder()
                    .processDefinitionKey("myProcess")
                    .transientVariable("countDownLatch", new CountDownLatch(2))
                    .start();
        })
                .isExactlyInstanceOf(FlowableException.class)
                .hasNoCause()
                .hasMessage("Countdown latch did not reach 0");

    }

    protected static class TestFutureJavaDelegate implements FlowableFutureJavaDelegate<Map<String, Object>, Map<String, Object>> {

        protected final CountDownLatch countDownLatch;

        @SuppressWarnings("unused") // used by the class delegate
        public TestFutureJavaDelegate() {
            this(null);
        }

        public TestFutureJavaDelegate(CountDownLatch countDownLatch) {
            this.countDownLatch = countDownLatch;
        }

        @Override
        public Map<String, Object> prepareExecutionData(DelegateExecution execution) {
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("beforeExecutionThreadName", Thread.currentThread().getName());
            AtomicInteger counter = (AtomicInteger) execution.getTransientVariable("counter");
            inputData.put("counter", counter.incrementAndGet());
            inputData.put("countDownLatch", execution.getTransientVariable("countDownLatch"));
            return inputData;
        }

        @Override
        public Map<String, Object> execute(Map<String, Object> inputData) {
            CountDownLatch countDownLatch = this.countDownLatch;
            if (countDownLatch == null) {
                countDownLatch = (CountDownLatch) inputData.get("countDownLatch");
            }
            countDownLatch.countDown();
            try {
                if (countDownLatch.await(2, TimeUnit.SECONDS)) {
                    int counter = (int) inputData.get("counter");
                    Map<String, Object> outputData = new HashMap<>();
                    outputData.put("executionThreadName" + counter, Thread.currentThread().getName());
                    outputData.put("counter" + counter, counter);
                    outputData.put("beforeExecutionThreadName" + counter, inputData.get("beforeExecutionThreadName"));
                    return outputData;
                } else {
                    throw new FlowableException("Countdown latch did not reach 0");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return null;
        }

        @Override
        public void afterExecution(DelegateExecution execution, Map<String, Object> executionData) {
            if (executionData != null) {
                for (Map.Entry<String, Object> entry : executionData.entrySet()) {
                    String key = entry.getKey();
                    execution.setVariable(key, entry.getValue());

                    if (key.startsWith("counter")) {
                        execution.setVariable("afterExecutionThreadName" + key.substring(7), Thread.currentThread().getName());
                    }
                }
            }
        }
    }

    protected static class TestJavaDelegate implements JavaDelegate {

        protected final CountDownLatch countDownLatch;

        @SuppressWarnings("unused") // used from the class delegate
        public TestJavaDelegate() {
            this(null);
        }

        public TestJavaDelegate(CountDownLatch countDownLatch) {
            this.countDownLatch = countDownLatch;
        }

        @Override
        public void execute(DelegateExecution execution) {
            CountDownLatch countDownLatch = this.countDownLatch;
            if (countDownLatch == null) {
                countDownLatch = (CountDownLatch) execution.getTransientVariable("countDownLatch");
            }
            countDownLatch.countDown();
            try {
                if (countDownLatch.await(2, TimeUnit.SECONDS)) {
                    // Nothing to do
                } else {
                    throw new FlowableException("Countdown latch did not reach 0");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    protected static class TestBeanReturnsFuture {

        protected final CountDownLatch countDownLatch;
        protected final AsyncTaskExecutor asyncTaskExecutor;

        public TestBeanReturnsFuture(CountDownLatch countDownLatch, AsyncTaskExecutor asyncTaskExecutor) {
            this.countDownLatch = countDownLatch;
            this.asyncTaskExecutor = asyncTaskExecutor;
        }

        public Future<String> invoke() {
            return asyncTaskExecutor.submit(() -> {
                countDownLatch.countDown();
                try {
                    if (countDownLatch.await(2, TimeUnit.SECONDS)) {
                        return null;
                    } else {
                        throw new FlowableException("Countdown latch did not reach 0");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        }
    }

    protected static class TestBean {

        protected final CountDownLatch countDownLatch;

        public TestBean(CountDownLatch countDownLatch) {
            this.countDownLatch = countDownLatch;
        }

        public String invoke() {
            countDownLatch.countDown();
            try {
                if (countDownLatch.await(3, TimeUnit.SECONDS)) {
                    return null;
                } else {
                    throw new FlowableException("Countdown latch did not reach 0");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
