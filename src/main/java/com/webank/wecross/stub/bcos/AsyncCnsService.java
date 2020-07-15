package com.webank.wecross.stub.bcos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wecross.stub.*;
import com.webank.wecross.stub.bcos.account.BCOSAccount;
import com.webank.wecross.stub.bcos.common.*;
import com.webank.wecross.stub.bcos.contract.FunctionUtility;
import com.webank.wecross.stub.bcos.contract.SignTransaction;
import com.webank.wecross.stub.bcos.protocol.request.TransactionParams;
import com.webank.wecross.stub.bcos.verify.MerkleValidation;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.fisco.bcos.web3j.abi.FunctionEncoder;
import org.fisco.bcos.web3j.abi.datatypes.Function;
import org.fisco.bcos.web3j.crypto.Credentials;
import org.fisco.bcos.web3j.precompile.cns.CnsInfo;
import org.fisco.bcos.web3j.protocol.ObjectMapperFactory;
import org.fisco.bcos.web3j.protocol.channel.StatusCode;
import org.fisco.bcos.web3j.protocol.core.methods.response.Call;
import org.fisco.bcos.web3j.protocol.core.methods.response.TransactionReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncCnsService {
    private static final Logger logger = LoggerFactory.getLogger(AsyncCnsService.class);

    private ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();

    private LRUCache<String, String> abiCache = new LRUCache<>(32);
    private ScheduledExecutorService scheduledExecutorService =
            new ScheduledThreadPoolExecutor(1024);
    private static final long CLEAR_EXPIRES = 30 * 60; // 30 min
    private Semaphore queryABISemaphore = new Semaphore(1, true);

    public AsyncCnsService() {
        this.scheduledExecutorService.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        abiCache.clear();
                    }
                },
                CLEAR_EXPIRES,
                CLEAR_EXPIRES,
                TimeUnit.SECONDS);
    }

    public interface QueryCallback {
        void onResponse(Exception e, String info);
    }

    public void queryAddress(String name, Connection connection, QueryCallback callback) {
        selectByName(
                name,
                connection,
                (exception, infoList) -> {
                    if (Objects.nonNull(exception)) {
                        callback.onResponse(exception, null);
                        return;
                    }

                    if (Objects.isNull(infoList) || infoList.isEmpty()) {
                        callback.onResponse(null, null);
                    } else {
                        int size = infoList.size();
                        callback.onResponse(null, infoList.get(size - 1).getAddress());
                    }
                });
    }

    public void queryABI(String name, Connection connection, QueryCallback callback) {
        try {
            final String abi = abiCache.get(name);
            if (abi != null) {
                // Just use thread pool to return by callback
                this.scheduledExecutorService.schedule(
                        new Runnable() {
                            @Override
                            public void run() {
                                callback.onResponse(null, abi);
                            }
                        },
                        0,
                        TimeUnit.MILLISECONDS);
                return;
            }

            queryABISemaphore.acquire(1); // Only 1 thread can query abi remote
            final String abiAgain = abiCache.get(name);
            if (abiAgain != null) {
                queryABISemaphore.release();
                // Just use thread pool to return by callback
                this.scheduledExecutorService.schedule(
                        new Runnable() {
                            @Override
                            public void run() {
                                callback.onResponse(null, abiAgain);
                            }
                        },
                        0,
                        TimeUnit.MILLISECONDS);
                return;
            }

            selectByName(
                    name,
                    connection,
                    (exception, infoList) -> {
                        if (Objects.nonNull(exception)) {
                            queryABISemaphore.release();
                            callback.onResponse(exception, null);
                            return;
                        }

                        if (Objects.isNull(infoList) || infoList.isEmpty()) {
                            queryABISemaphore.release();
                            callback.onResponse(null, null);
                        } else {
                            int size = infoList.size();
                            String currentAbi = infoList.get(size - 1).getAbi();
                            abiCache.put(name, currentAbi);
                            queryABISemaphore.release();

                            if (logger.isDebugEnabled()) {
                                logger.debug("queryABI name:{}, abi:{}", name, currentAbi);
                            }
                            callback.onResponse(null, currentAbi);
                        }
                    });
        } catch (Exception e) {
            queryABISemaphore.release();
            callback.onResponse(e, null);
        }
    }

    public interface SelectCallback {
        void onResponse(Exception e, List<CnsInfo> infoList);
    }

    public void selectByNameAndVersion(
            String name, String version, Connection connection, SelectCallback callback) {
        select(BCOSConstant.CNS_METHOD_SELECTBYNAMEANDVERSION, name, version, connection, callback);
    }

    public void selectByName(String name, Connection connection, SelectCallback callback) {
        select(BCOSConstant.CNS_METHOD_SELECTBYNAME, name, null, connection, callback);
    }

    private void select(
            String method,
            String name,
            String version,
            Connection connection,
            SelectCallback callback) {
        Function function;
        TransactionRequest transactionRequest = new TransactionRequest();
        TransactionParams.TP_YPE tp_ype = null;
        if (Objects.nonNull(version)) {
            function = FunctionUtility.newCNSSelectByNameAndVersionFunction(method, name, version);
            transactionRequest.setArgs(new String[] {name, version});
            tp_ype = TransactionParams.TP_YPE.CNS_SELECT_BY_NAME;
        } else {
            function = FunctionUtility.newCNSSelectByNameFunction(method, name);
            transactionRequest.setArgs(new String[] {name});
            tp_ype = TransactionParams.TP_YPE.CNS_SELECT_BY_NAME_AND_VERSION;
        }

        transactionRequest.setMethod(method);
        TransactionParams transaction =
                new TransactionParams(transactionRequest, FunctionEncoder.encode(function), tp_ype);

        transaction.setTo(BCOSConstant.CNS_PRECOMPILED_ADDRESS);
        transaction.setFrom(BCOSConstant.DEFAULT_ADDRESS);

        Request request = null;
        try {
            request =
                    RequestFactory.requestBuilder(
                            BCOSRequestType.CALL, objectMapper.writeValueAsBytes(transaction));
        } catch (Exception e) {
            logger.warn("exception occurs", e);
            callback.onResponse(e, null);
        }

        connection.asyncSend(
                request,
                connectionResponse -> {
                    try {
                        if (connectionResponse.getErrorCode() != BCOSStatusCode.Success) {
                            callback.onResponse(
                                    new Exception(connectionResponse.getErrorMessage()), null);
                            return;
                        }

                        Call.CallOutput callOutput =
                                objectMapper.readValue(
                                        connectionResponse.getData(), Call.CallOutput.class);

                        if (logger.isDebugEnabled()) {
                            logger.debug(
                                    "call result, status: {}, blockNumber: {}",
                                    callOutput.getStatus(),
                                    callOutput.getCurrentBlockNumber());
                        }

                        if (StatusCode.Success.equals(callOutput.getStatus())) {
                            String cnsInfo =
                                    FunctionUtility.decodeOutputAsString(callOutput.getOutput());
                            List<CnsInfo> infoList =
                                    objectMapper.readValue(
                                            cnsInfo,
                                            objectMapper
                                                    .getTypeFactory()
                                                    .constructCollectionType(
                                                            List.class, CnsInfo.class));
                            callback.onResponse(null, infoList);
                        } else {
                            callback.onResponse(new Exception(callOutput.getStatus()), null);
                        }
                    } catch (Exception e) {
                        logger.warn("exception occurs", e);
                        callback.onResponse(new Exception(e.getMessage()), null);
                    }
                });
    }

    public interface InsertCallback {
        void onResponse(Exception e);
    }

    public void insert(
            String name,
            String address,
            String version,
            String abi,
            Account account,
            BlockHeaderManager blockHeaderManager,
            Connection connection,
            InsertCallback callback) {

        Map<String, String> properties = connection.getProperties();
        int groupId = Integer.parseInt(properties.get(BCOSConstant.BCOS_GROUP_ID));
        int chainId = Integer.parseInt(properties.get(BCOSConstant.BCOS_CHAIN_ID));

        blockHeaderManager.asyncGetBlockNumber(
                (exception, blockNumber) -> {
                    if (Objects.nonNull(exception)) {
                        callback.onResponse(new Exception("getting block number failed"));
                        return;
                    }

                    BCOSAccount bcosAccount = (BCOSAccount) account;
                    Credentials credentials = bcosAccount.getCredentials();

                    Function function =
                            FunctionUtility.newCNSInsertFunction(
                                    BCOSConstant.CNS_METHOD_INSERT, name, version, address, abi);
                    // get signed transaction hex string
                    String signTx =
                            SignTransaction.sign(
                                    credentials,
                                    BCOSConstant.CNS_PRECOMPILED_ADDRESS,
                                    BigInteger.valueOf(groupId),
                                    BigInteger.valueOf(chainId),
                                    BigInteger.valueOf(blockNumber),
                                    FunctionEncoder.encode(function));

                    if (logger.isDebugEnabled()) {
                        logger.debug(
                                "register cns, name: {}, version: {}, address: {}, abi: {}, blockNumber: {}",
                                name,
                                version,
                                address,
                                abi,
                                blockNumber);
                    }

                    TransactionRequest transactionRequest = new TransactionRequest();
                    transactionRequest.setMethod(BCOSConstant.CNS_METHOD_INSERT);
                    transactionRequest.setArgs(new String[] {name, version, address, abi});
                    TransactionParams transaction =
                            new TransactionParams(
                                    transactionRequest,
                                    signTx,
                                    TransactionParams.TP_YPE.CNS_INSERT);
                    Request request;
                    try {
                        request =
                                RequestFactory.requestBuilder(
                                        BCOSRequestType.SEND_TRANSACTION,
                                        objectMapper.writeValueAsBytes(transaction));
                    } catch (Exception e) {
                        logger.warn("exception occurs", e);
                        callback.onResponse(e);
                        return;
                    }
                    connection.asyncSend(
                            request,
                            response -> {
                                try {
                                    if (response.getErrorCode() != BCOSStatusCode.Success) {
                                        throw new BCOSStubException(
                                                response.getErrorCode(),
                                                response.getErrorMessage());
                                    }

                                    TransactionReceipt receipt =
                                            objectMapper.readValue(
                                                    response.getData(), TransactionReceipt.class);

                                    if (receipt.isStatusOK()) {
                                        blockHeaderManager.asyncGetBlockHeader(
                                                receipt.getBlockNumber().longValue(),
                                                (blockHeaderException, blockHeader) -> {
                                                    if (Objects.nonNull(blockHeaderException)) {
                                                        callback.onResponse(
                                                                new Exception(
                                                                        "getting block header failed"));
                                                        return;
                                                    }
                                                    MerkleValidation merkleValidation =
                                                            new MerkleValidation();
                                                    try {
                                                        merkleValidation
                                                                .verifyTransactionReceiptProof(
                                                                        receipt.getBlockNumber()
                                                                                .longValue(),
                                                                        receipt
                                                                                .getTransactionHash(),
                                                                        blockHeader,
                                                                        receipt);
                                                    } catch (BCOSStubException e) {
                                                        logger.warn(
                                                                "verifying transaction of register failed",
                                                                e);
                                                        callback.onResponse(
                                                                new Exception(
                                                                        "verifying transaction of register failed"));
                                                        return;
                                                    }

                                                    callback.onResponse(null);
                                                });
                                    } else {
                                        callback.onResponse(new Exception(receipt.getStatus()));
                                    }
                                } catch (Exception e) {
                                    logger.warn("exception occurs", e);
                                    callback.onResponse(e);
                                }
                            });
                });
    }
}
