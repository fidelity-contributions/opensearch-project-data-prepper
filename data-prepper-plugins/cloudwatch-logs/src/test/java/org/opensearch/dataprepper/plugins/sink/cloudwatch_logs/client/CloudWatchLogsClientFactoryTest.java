/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.sink.cloudwatch_logs.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.opensearch.dataprepper.aws.api.AwsCredentialsOptions;
import org.opensearch.dataprepper.aws.api.AwsCredentialsSupplier;
import org.opensearch.dataprepper.plugins.sink.cloudwatch_logs.config.AwsConfig;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClientBuilder;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

class CloudWatchLogsClientFactoryTest {
    private AwsConfig mockAwsConfig;
    private AwsCredentialsSupplier mockAwsCredentialsSupplier;
    private AwsCredentialsOptions mockAwsCredentialsOptions;
    private AwsCredentialsProvider mockAwsCredentialsProvider;
    private Map<String, String> mockCustomHeaders;

    @BeforeEach
    void setUp() {
        mockAwsConfig = mock(AwsConfig.class);
        mockAwsCredentialsSupplier = mock(AwsCredentialsSupplier.class);
        mockAwsCredentialsOptions = mock(AwsCredentialsOptions.class);
        mockAwsCredentialsProvider = mock(AwsCredentialsProvider.class);
        mockCustomHeaders = new HashMap<>();
        mockCustomHeaders.put("X-Test-Header", "test-value");
        when(mockAwsConfig.getAwsRegion()).thenReturn(Region.US_EAST_1);
        when(mockAwsCredentialsSupplier.getDefaultRegion()).thenReturn(Optional.of(Region.US_EAST_1));
    }

    @Test
    void GIVEN_default_credentials_SHOULD_return_non_null_client() {
        when(mockAwsCredentialsSupplier.getProvider(any())).thenReturn(mockAwsCredentialsProvider);
        final CloudWatchLogsClient cloudWatchLogsClientToTest = CloudWatchLogsClientFactory.createCwlClient(mockAwsConfig, mockAwsCredentialsSupplier);

        assertNotNull(cloudWatchLogsClientToTest);
    }

    @Test
    void GIVEN_default_credentials_with_no_provider_SHOULD_return_null_client() {
        final CloudWatchLogsClient cloudWatchLogsClientToTest = CloudWatchLogsClientFactory.createCwlClient(mockAwsConfig, mockAwsCredentialsSupplier);

        assertNull(cloudWatchLogsClientToTest);
    }

    @Test
    void GIVEN_default_credentials_with_no_region_SHOULD_return_null_client() {
        when(mockAwsConfig.getAwsRegion()).thenReturn(null);
        final CloudWatchLogsClient cloudWatchLogsClientToTest = CloudWatchLogsClientFactory.createCwlClient(mockAwsConfig, mockAwsCredentialsSupplier);

        assertNull(cloudWatchLogsClientToTest);
    }

    @Test
    void GIVEN_valid_credential_and_aws_parameters_SHOULD_generate_valid_provider_and_options() {
        final String stsRoleArn = UUID.randomUUID().toString();
        final String externalId = UUID.randomUUID().toString();
        final Map<String, String> stsHeaderOverrides = Map.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        when(mockAwsConfig.getAwsStsRoleArn()).thenReturn(stsRoleArn);
        when(mockAwsConfig.getAwsStsExternalId()).thenReturn(externalId);
        when(mockAwsConfig.getAwsStsHeaderOverrides()).thenReturn(stsHeaderOverrides);

        final AwsCredentialsProvider expectedCredentialsProvider = mock(AwsCredentialsProvider.class);
        when(mockAwsCredentialsSupplier.getProvider(ArgumentMatchers.any())).thenReturn(expectedCredentialsProvider);

        final CloudWatchLogsClientBuilder cloudWatchLogsClientBuilder = mock(CloudWatchLogsClientBuilder.class);
        when(cloudWatchLogsClientBuilder.region(mockAwsConfig.getAwsRegion())).thenReturn(cloudWatchLogsClientBuilder);
        when(cloudWatchLogsClientBuilder.credentialsProvider(ArgumentMatchers.any())).thenReturn(cloudWatchLogsClientBuilder);
        when(cloudWatchLogsClientBuilder.overrideConfiguration(ArgumentMatchers.any(ClientOverrideConfiguration.class))).thenReturn(cloudWatchLogsClientBuilder);
        try(final MockedStatic<CloudWatchLogsClient> cloudWatchLogsClientMockedStatic = mockStatic(CloudWatchLogsClient.class)) {
            cloudWatchLogsClientMockedStatic.when(CloudWatchLogsClient::builder)
                    .thenReturn(cloudWatchLogsClientBuilder);
            CloudWatchLogsClientFactory.createCwlClient(mockAwsConfig, mockAwsCredentialsSupplier);
        }

        final ArgumentCaptor<AwsCredentialsProvider> credentialsProviderArgumentCaptor = ArgumentCaptor.forClass(AwsCredentialsProvider.class);
        verify(cloudWatchLogsClientBuilder).credentialsProvider(credentialsProviderArgumentCaptor.capture());

        final AwsCredentialsProvider actualProvider = credentialsProviderArgumentCaptor.getValue();

        assertThat(actualProvider, equalTo(expectedCredentialsProvider));

        final ArgumentCaptor<AwsCredentialsOptions> credentialsOptionsArgumentCaptor = ArgumentCaptor.forClass(AwsCredentialsOptions.class);
        verify(mockAwsCredentialsSupplier).getProvider(credentialsOptionsArgumentCaptor.capture());

        final AwsCredentialsOptions actualOptions = credentialsOptionsArgumentCaptor.getValue();
        assertThat(actualOptions.getRegion(), equalTo(mockAwsConfig.getAwsRegion()));
        assertThat(actualOptions.getStsRoleArn(), equalTo(mockAwsConfig.getAwsStsRoleArn()));
        assertThat(actualOptions.getStsExternalId(), equalTo(mockAwsConfig.getAwsStsExternalId()));
        assertThat(actualOptions.getStsHeaderOverrides(), equalTo(mockAwsConfig.getAwsStsHeaderOverrides()));
    }

    @Test
    void GIVEN_custom_headers_SHOULD_create_client_with_custom_headers() {
        // Arrange
        final Map<String, String> customHeaders = Map.of(
            "X-Custom-Header", "custom-value",
            "X-Request-ID", "request-123"
        );
        when(mockAwsCredentialsSupplier.getProvider(any())).thenReturn(mockAwsCredentialsProvider);

        final CloudWatchLogsClientBuilder cloudWatchLogsClientBuilder = mock(CloudWatchLogsClientBuilder.class);
        when(cloudWatchLogsClientBuilder.region(any())).thenReturn(cloudWatchLogsClientBuilder);
        when(cloudWatchLogsClientBuilder.credentialsProvider(any())).thenReturn(cloudWatchLogsClientBuilder);
        when(cloudWatchLogsClientBuilder.overrideConfiguration(any(ClientOverrideConfiguration.class))).thenReturn(cloudWatchLogsClientBuilder);
        
        try(final MockedStatic<CloudWatchLogsClient> cloudWatchLogsClientMockedStatic = mockStatic(CloudWatchLogsClient.class)) {
            cloudWatchLogsClientMockedStatic.when(CloudWatchLogsClient::builder)
                    .thenReturn(cloudWatchLogsClientBuilder);

            // Act
            CloudWatchLogsClientFactory.createCwlClient(mockAwsConfig, mockAwsCredentialsSupplier, customHeaders);

            // Assert
            final ArgumentCaptor<ClientOverrideConfiguration> configCaptor = ArgumentCaptor.forClass(ClientOverrideConfiguration.class);
            verify(cloudWatchLogsClientBuilder).overrideConfiguration(configCaptor.capture());
            
            final ClientOverrideConfiguration actualConfig = configCaptor.getValue();
            assertNotNull(actualConfig);
            
            // Verify that headers are configured
            assertThat(actualConfig.headers().get("X-Custom-Header"), equalTo(List.of("custom-value")));
            assertThat(actualConfig.headers().get("X-Request-ID"), equalTo(List.of("request-123")));
        }
    }

    @Test
    void GIVEN_empty_custom_headers_SHOULD_create_client_without_custom_headers() {
        // Arrange
        final Map<String, String> emptyHeaders = new HashMap<>();
        when(mockAwsCredentialsSupplier.getProvider(any())).thenReturn(mockAwsCredentialsProvider);

        final CloudWatchLogsClientBuilder cloudWatchLogsClientBuilder = mock(CloudWatchLogsClientBuilder.class);
        when(cloudWatchLogsClientBuilder.region(any())).thenReturn(cloudWatchLogsClientBuilder);
        when(cloudWatchLogsClientBuilder.credentialsProvider(any())).thenReturn(cloudWatchLogsClientBuilder);
        when(cloudWatchLogsClientBuilder.overrideConfiguration(any(ClientOverrideConfiguration.class))).thenReturn(cloudWatchLogsClientBuilder);
        
        try(final MockedStatic<CloudWatchLogsClient> cloudWatchLogsClientMockedStatic = mockStatic(CloudWatchLogsClient.class)) {
            cloudWatchLogsClientMockedStatic.when(CloudWatchLogsClient::builder)
                    .thenReturn(cloudWatchLogsClientBuilder);

            // Act
            CloudWatchLogsClientFactory.createCwlClient(mockAwsConfig, mockAwsCredentialsSupplier, emptyHeaders);

            // Assert
            final ArgumentCaptor<ClientOverrideConfiguration> configCaptor = ArgumentCaptor.forClass(ClientOverrideConfiguration.class);
            verify(cloudWatchLogsClientBuilder).overrideConfiguration(configCaptor.capture());
            
            final ClientOverrideConfiguration actualConfig = configCaptor.getValue();
            assertNotNull(actualConfig);
            
            // Verify that no custom headers are configured
            assertThat(actualConfig.headers().isEmpty(), is(true));
        }
    }
}
