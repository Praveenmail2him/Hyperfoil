/*
 * Copyright 2018 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
package io.sailrocket.core.builder;

import io.sailrocket.api.config.Benchmark;
import io.sailrocket.api.config.Phase;
import io.sailrocket.api.config.Sequence;
import io.sailrocket.api.config.Step;
import io.sailrocket.core.parser.BenchmarkParser;
import io.sailrocket.core.parser.ParserException;
import io.sailrocket.core.steps.AwaitIntStep;
import io.sailrocket.core.steps.HttpRequestStep;
import io.sailrocket.core.steps.NoopStep;
import io.sailrocket.core.steps.ScheduleDelayStep;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Percentage.withPercentage;
import static org.junit.Assert.fail;

public class YamlParserTest {
    @Test
    public void testSimpleYaml() {
        Benchmark benchmark = buildBenchmark("scenarios/simple.yaml");
        assertThat(benchmark.name()).isEqualTo("simple benchmark");
        Phase[] phases = benchmark.simulation().phases().toArray(new Phase[0]);
        assertThat(phases.length).isEqualTo(3);
        Sequence[] sequences = phases[0].scenario().sequences();
        assertThat(sequences.length).isEqualTo(1);
        Step[] steps = sequences[0].steps();
        assertThat(steps.length).isEqualTo(5);
        assertThat(steps[0]).isInstanceOf(HttpRequestStep.class);
        assertThat(steps[1]).isInstanceOf(HttpRequestStep.class);
        assertThat(steps[2]).isInstanceOf(NoopStep.class);
        assertThat(steps[3]).isInstanceOf(AwaitIntStep.class);
        assertThat(steps[4]).isInstanceOf(ScheduleDelayStep.class);
    }

    @Test
    public void testComplexYaml() {
        Benchmark benchmark = buildBenchmark("scenarios/complex.yaml");
        assertThat(benchmark.name()).isEqualTo("complex benchmark");
        assertThat(benchmark.agents().length).isEqualTo(3);

        double sumWeights = 0.2 + 0.8 + 0.1 + 1;
        assertThat(phase(benchmark, "steadyState/invalidRegistration", Phase.ConstantPerSec.class).usersPerSec)
              .isCloseTo(100.0 / 3 / sumWeights * 0.2, withPercentage(1));
        assertThat(phase(benchmark, "steadyState/validRegistration", Phase.ConstantPerSec.class).usersPerSec)
              .isCloseTo(100.0 / 3 / sumWeights * 0.8, withPercentage(1));
        assertThat(phase(benchmark, "steadyState/unregister", Phase.ConstantPerSec.class).usersPerSec)
              .isCloseTo(100.0 / 3 / sumWeights * 0.1, withPercentage(1));
        assertThat(phase(benchmark, "steadyState/viewUser", Phase.ConstantPerSec.class).usersPerSec)
              .isCloseTo(100.0 / 3 / sumWeights * 1.0, withPercentage(1));
        assertThat(benchmark.simulation().phases().stream()
              .filter(p -> p instanceof Phase.ConstantPerSec)
              .mapToDouble(p -> ((Phase.ConstantPerSec) p).usersPerSec)
              .sum()).isCloseTo(100.0 / 3, withPercentage(1));
    }

    private <T extends Phase> T phase(Benchmark benchmark, String name, Class<T> type) {
        Phase phase = benchmark.simulation().phases().stream()
              .filter(p -> p.name().equals(name)).findFirst().get();
        assertThat(phase).isInstanceOf(type);
        return (T) phase;
    }

    @Test
    public void testIterationYaml() {
        Benchmark benchmark = buildBenchmark("scenarios/iteration.yaml");
        assertThat(benchmark.name()).isEqualTo("iteration benchmark");
    }
    @Test
    public void testAwaitDelayYaml() {
        Benchmark benchmark = buildBenchmark("scenarios/awaitDelay.yaml");
        assertThat(benchmark.name()).isEqualTo("await delay benchmark");
    }

    private Benchmark buildBenchmark(String s) {
        return buildBenchmark(this.getClass().getClassLoader().getResourceAsStream(s));
    }

    private Benchmark buildBenchmark(InputStream inputStream){
        if (inputStream == null)
            fail("Could not find benchmark configuration");

        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            String source = result.toString(StandardCharsets.UTF_8.name());
            Benchmark benchmark = BenchmarkParser.instance().buildBenchmark(source);

            Assert.assertNotNull(benchmark);

            return benchmark;
        } catch (ParserException | IOException e) {
            e.printStackTrace();
            fail("Error occurred during parsing");
        }
        return null;
    }
}
