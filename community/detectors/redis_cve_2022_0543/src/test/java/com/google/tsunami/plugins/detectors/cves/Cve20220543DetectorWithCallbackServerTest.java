/*
 * Copyright 2020 Google LLC
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
package com.google.tsunami.plugins.detectors.cves;

import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static com.google.tsunami.common.data.NetworkEndpointUtils.forIpAndPort;
import static com.google.tsunami.plugins.detectors.cves.Cve20220543Detector.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.common.net.http.HttpClientModule;
import com.google.tsunami.common.time.testing.FakeUtcClock;
import com.google.tsunami.common.time.testing.FakeUtcClockModule;
import com.google.tsunami.plugin.payload.testing.FakePayloadGeneratorModule;
import com.google.tsunami.plugin.payload.testing.PayloadTestHelper;
import com.google.tsunami.proto.DetectionReport;
import com.google.tsunami.proto.DetectionReportList;
import com.google.tsunami.proto.DetectionStatus;
import com.google.tsunami.proto.NetworkService;
import com.google.tsunami.proto.Severity;
import com.google.tsunami.proto.TargetInfo;
import com.google.tsunami.proto.Vulnerability;
import com.google.tsunami.proto.VulnerabilityId;
import java.io.IOException;
import java.time.Instant;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Unit tests for {@link Cve20220543Detector}, showing how to test a detector which utilizes the
 * payload generator framework.
 */
@RunWith(JUnit4.class)
public final class Cve20220543DetectorWithCallbackServerTest {
  private final FakeUtcClock fakeUtcClock =
      FakeUtcClock.create().setNow(Instant.parse("2020-01-01T00:00:00.00Z"));

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Inject private Cve20220543Detector detector;
  @Mock private JedisPoolFactory mockJedisPoolFactory;
  @Mock private JedisPool mockJedisPool;
  @Mock private Jedis mockJedis;
  private MockWebServer mockCallbackServer;

  @Before
  public void setUp() throws IOException {
    when(mockJedisPoolFactory.create(any())).thenReturn(mockJedisPool);
    when(mockJedisPool.getResource()).thenReturn(mockJedis);
    when(mockJedis.eval(anyString())).thenReturn("");

    mockCallbackServer = new MockWebServer();
    mockCallbackServer.start();

    Guice.createInjector(
            new FakeUtcClockModule(fakeUtcClock),
            new HttpClientModule.Builder().build(),
            FakePayloadGeneratorModule.builder().setCallbackServer(mockCallbackServer).build(),
            new Cve20220543DetectorBootstrapModule(),
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(JedisPoolFactory.class).toInstance(mockJedisPoolFactory);
              }
            })
        .injectMembers(this);
  }

  @After
  public void tearDown() throws Exception {
    mockCallbackServer.shutdown();
  }

  @Test
  public void detect_whenVulnerable_reportsVulnerability() throws IOException {
    NetworkService service =
        NetworkService.newBuilder()
            .setNetworkEndpoint(forIpAndPort("127.0.0.1", 6379))
            .setServiceName("redis")
            .build();
    TargetInfo target =
        TargetInfo.newBuilder().addNetworkEndpoints(service.getNetworkEndpoint()).build();
    mockCallbackServer.enqueue(PayloadTestHelper.generateMockSuccessfulCallbackResponse());

    DetectionReportList detectionReports = detector.detect(target, ImmutableList.of(service));

    assertThat(detectionReports.getDetectionReportsList())
        .containsExactly(
            DetectionReport.newBuilder()
                .setTargetInfo(target)
                .setNetworkService(service)
                .setDetectionTimestamp(
                    Timestamps.fromMillis(Instant.now(fakeUtcClock).toEpochMilli()))
                .setDetectionStatus(DetectionStatus.VULNERABILITY_VERIFIED)
                .setVulnerability(
                    Vulnerability.newBuilder()
                        .setMainId(
                            VulnerabilityId.newBuilder()
                                .setPublisher("TSUNAMI_COMMUNITY")
                                .setValue("CVE_2022_0543"))
                        .setSeverity(Severity.CRITICAL)
                        .setTitle(TITLE)
                        .setDescription(DESCRIPTION)
                        .setRecommendation(RECOMMENDATION))
                .build());
    verify(mockJedis, times(1)).eval(anyString());
  }

  @Test
  public void detect_whenNotVulnerable_doesNotReportVulnerability() throws IOException {
    NetworkService service =
        NetworkService.newBuilder()
            .setNetworkEndpoint(forIpAndPort("127.0.0.1", 6379))
            .setServiceName("redis")
            .build();
    TargetInfo target =
        TargetInfo.newBuilder().addNetworkEndpoints(service.getNetworkEndpoint()).build();
    mockCallbackServer.enqueue(PayloadTestHelper.generateMockUnsuccessfulCallbackResponse());

    DetectionReportList detectionReports = detector.detect(target, ImmutableList.of(service));

    assertThat(detectionReports.getDetectionReportsList()).isEmpty();

    verify(mockJedis, times(1)).eval(anyString());
  }
}
