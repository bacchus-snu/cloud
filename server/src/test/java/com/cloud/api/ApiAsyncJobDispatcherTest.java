// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.api;

import java.util.Map;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.AsyncJobResponse;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.jobs.JobInfo;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.response.ApiResponseSerializer;
import com.cloud.dc.DataCenter;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.VolumeMoveRequiredException;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.EntityManager;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ApiAsyncJobDispatcherTest {

    @APICommand(name = "failingJobTest", description = "A command whose job fails", responseObject = org.apache.cloudstack.api.response.SuccessResponse.class)
    public static class FailingCmd extends BaseAsyncCmd {
        @Override
        public String getEventType() {
            return "TEST.EVENT";
        }

        @Override
        public String getEventDescription() {
            return "a job that fails";
        }

        @Override
        public void execute() {
        }

        @Override
        public long getEntityOwnerId() {
            return 1L;
        }
    }

    @Mock
    private ApiDispatcher dispatcher;
    @Mock
    private AsyncJobManager asyncJobMgr;
    @Mock
    private EntityManager entityMgr;
    @Mock
    private AsyncJob job;

    @InjectMocks
    private ApiAsyncJobDispatcher jobDispatcher = new ApiAsyncJobDispatcher();

    private MockedStatic<ComponentContext> componentContext;

    @Before
    public void setUp() {
        componentContext = Mockito.mockStatic(ComponentContext.class);
        componentContext.when(() -> ComponentContext.inject(Mockito.any(FailingCmd.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(job.getId()).thenReturn(7L);
        Mockito.when(job.getCmd()).thenReturn(FailingCmd.class.getName());
        Mockito.when(job.getCmdInfo()).thenReturn("{\"ctxUserId\":\"2\",\"ctxAccountId\":\"3\"}");
        Mockito.when(entityMgr.findById(Mockito.eq(User.class), Mockito.anyLong())).thenReturn(Mockito.mock(User.class));
        Mockito.when(entityMgr.findById(Mockito.eq(Account.class), Mockito.anyLong())).thenReturn(Mockito.mock(Account.class));
    }

    @After
    public void tearDown() {
        componentContext.close();
    }

    private String failedResult(Throwable thrown, int expectedStatusCode) throws Exception {
        Mockito.doThrow(thrown).when(dispatcher).dispatch(Mockito.any(FailingCmd.class), Mockito.<Map<String, String>>any(), Mockito.eq(true));

        jobDispatcher.runJob(job);

        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        Mockito.verify(asyncJobMgr).completeAsyncJob(Mockito.eq(7L), Mockito.eq(JobInfo.Status.FAILED), Mockito.eq(expectedStatusCode), result.capture());
        return result.getValue();
    }

    @Test
    public void failedJobCarriesTheErrorCodeOfAMoveThatNeedsApproval() throws Exception {
        ServerApiException refused = new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, "the disks must move first",
                new VolumeMoveRequiredException("the disks must move first", 1L, null));

        String result = failedResult(refused, ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR.getHttpCode());

        Assert.assertTrue(result, result.contains("\"cserrorcode\":4336"));
        Assert.assertTrue(result, result.contains("\"errorcode\":" + ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR.getHttpCode()));
        Assert.assertTrue(result, result.contains("the disks must move first"));
    }

    @Test
    public void failedJobCarriesTheErrorCodeOfAnExceptionThatWasNotWrapped() throws Exception {
        String result = failedResult(new InsufficientServerCapacityException("no room", DataCenter.class, 1L), ApiErrorCode.INTERNAL_ERROR.getHttpCode());

        Assert.assertTrue(result, result.contains("\"cserrorcode\":" + new InsufficientServerCapacityException("x", DataCenter.class, 1L).getCSErrorCode()));
    }

    @Test
    public void failedJobKeepsTheErrorCodeOfAServerApiExceptionWithoutACause() throws Exception {
        ServerApiException plain = new ServerApiException(ApiErrorCode.PARAM_ERROR, "bad parameter");

        String result = failedResult(plain, ApiErrorCode.PARAM_ERROR.getHttpCode());

        Assert.assertTrue(result, result.contains("\"cserrorcode\":" + plain.getCSErrorCode()));
    }

    // what queryAsyncJobResult returns for the stored result: the job response as AsyncJobJoinDaoImpl builds it, serialized as the API does
    private String served(String storedResult, int resultCode) {
        AsyncJobResponse response = new AsyncJobResponse();
        response.setResponseName("queryasyncjobresultresponse");
        response.setJobResultCode(resultCode);
        response.setJobResultType("object");
        response.setJobResult((ResponseObject)ApiSerializerHelper.fromSerializedString(storedResult));
        return ApiResponseSerializer.toSerializedString(response, "json");
    }

    @Test
    public void queryAsyncJobResultServesTheErrorCodeOfAFailedJob() throws Exception {
        ServerApiException refused = new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, "the disks must move first",
                new VolumeMoveRequiredException("the disks must move first", 1L, null));
        int statusCode = ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR.getHttpCode();

        String json = served(failedResult(refused, statusCode), statusCode);

        Assert.assertTrue(json, json.contains("\"jobresult\":{\"errorcode\":533,\"cserrorcode\":4336,\"errortext\":\"the disks must move first\"}"));
    }

    @Test
    public void queryAsyncJobResultServesNoErrorCodeWhenTheFailureHasNone() throws Exception {
        String json = served(failedResult(new IllegalStateException("broken"), ApiErrorCode.INTERNAL_ERROR.getHttpCode()), ApiErrorCode.INTERNAL_ERROR.getHttpCode());

        Assert.assertTrue(json, json.contains("\"jobresult\":{\"errorcode\":530,\"errortext\":\"broken\"}"));
        Assert.assertFalse(json, json.contains("cserrorcode"));
    }

    @Test
    public void failedJobOfAnUnexpectedExceptionHasNoCloudErrorCode() throws Exception {
        String result = failedResult(new IllegalStateException("broken"), ApiErrorCode.INTERNAL_ERROR.getHttpCode());

        Assert.assertFalse(result, result.contains("cserrorcode"));
        Assert.assertTrue(result, result.contains("broken"));
    }
}
