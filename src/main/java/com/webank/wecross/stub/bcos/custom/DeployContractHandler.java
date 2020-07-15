package com.webank.wecross.stub.bcos.custom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wecross.stub.*;
import com.webank.wecross.stub.bcos.AsyncCnsService;
import com.webank.wecross.stub.bcos.abi.ABICodecJsonWrapper;
import com.webank.wecross.stub.bcos.abi.ABIDefinition;
import com.webank.wecross.stub.bcos.abi.ABIDefinitionFactory;
import com.webank.wecross.stub.bcos.abi.ABIObject;
import com.webank.wecross.stub.bcos.abi.ABIObjectFactory;
import com.webank.wecross.stub.bcos.abi.ContractABIDefinition;
import com.webank.wecross.stub.bcos.account.BCOSAccount;
import com.webank.wecross.stub.bcos.common.*;
import com.webank.wecross.stub.bcos.contract.SignTransaction;
import com.webank.wecross.stub.bcos.protocol.request.TransactionParams;
import com.webank.wecross.stub.bcos.verify.MerkleValidation;
import java.io.*;
import java.math.BigInteger;
import java.util.*;
import org.fisco.bcos.web3j.crypto.Credentials;
import org.fisco.bcos.web3j.crypto.EncryptType;
import org.fisco.bcos.web3j.protocol.ObjectMapperFactory;
import org.fisco.bcos.web3j.protocol.core.methods.response.TransactionReceipt;
import org.fisco.solc.compiler.CompilationResult;
import org.fisco.solc.compiler.SolidityCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeployContractHandler implements CommandHandler {
    private static final Logger logger = LoggerFactory.getLogger(DeployContractHandler.class);

    private ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();

    private AsyncCnsService asyncCnsService = new AsyncCnsService();
    private ABICodecJsonWrapper abiCodecJsonWrapper = new ABICodecJsonWrapper();

    /** @param args contractBytes || version */
    @Override
    public void handle(
            Path path,
            Object[] args,
            Account account,
            BlockHeaderManager blockHeaderManager,
            Connection connection,
            Map<String, String> abiMap,
            Driver.CustomCommandCallback callback) {

        if (Objects.isNull(args) || args.length < 4) {
            callback.onResponse(new Exception("incomplete args"), null);
            return;
        }

        String cnsName = (String) args[0];
        String sourceContent = (String) args[1];
        String className = (String) args[2];
        String version = (String) args[3];

        /** constructor params */
        List<String> params = null;
        if (args.length > 4) {
            params = new ArrayList<>();
            for (int i = 4; i < args.length; ++i) {
                params.add((String) args[i]);
            }
        }

        BCOSAccount bcosAccount = (BCOSAccount) account;
        Credentials credentials = bcosAccount.getCredentials();

        final List<String> finalParams = params;
        // check version
        checkContractVersion(
                cnsName,
                version,
                connection,
                checkVersionException -> {
                    if (Objects.nonNull(checkVersionException)) {
                        callback.onResponse(
                                new Exception(
                                        "checking contract version failed: "
                                                + checkVersionException.getMessage()),
                                null);
                        return;
                    }

                    CompilationResult.ContractMetadata metadata;
                    try {
                        boolean sm = EncryptType.encryptType != 0;

                        File sourceFile =
                                File.createTempFile("BCOSContract-", "-" + cnsName + ".sol");
                        OutputStream outputStream = new FileOutputStream(sourceFile);
                        outputStream.write(sourceContent.getBytes());
                        outputStream.close();

                        // compile contract
                        SolidityCompiler.Result res =
                                SolidityCompiler.compile(
                                        sourceFile,
                                        sm,
                                        true,
                                        SolidityCompiler.Options.ABI,
                                        SolidityCompiler.Options.BIN,
                                        SolidityCompiler.Options.INTERFACE,
                                        SolidityCompiler.Options.METADATA);

                        if (res.isFailed()) {
                            callback.onResponse(
                                    new Exception("compiling contract failed, " + res.getErrors()),
                                    res.getErrors());
                            return;
                        }

                        CompilationResult result = CompilationResult.parse(res.getOutput());
                        metadata = result.getContract(className);
                    } catch (IOException e) {
                        logger.error("compiling contract failed", e);
                        callback.onResponse(new Exception("compiling contract failed"), null);
                        return;
                    }

                    Map<String, String> properties = connection.getProperties();
                    int groupID = Integer.parseInt(properties.get(BCOSConstant.BCOS_GROUP_ID));
                    int chainID = Integer.parseInt(properties.get(BCOSConstant.BCOS_CHAIN_ID));

                    ContractABIDefinition contractABIDefinition =
                            ABIDefinitionFactory.loadABI(metadata.abi);
                    ABIDefinition constructor = contractABIDefinition.getConstructor();

                    /** check if solidity constructor needs arguments */
                    String paramsABI = "";
                    if (!Objects.isNull(constructor)
                            && !Objects.isNull(constructor.getInputs())
                            && !constructor.getInputs().isEmpty()) {

                        if (Objects.isNull(finalParams)) {
                            logger.error(" {} constructor needs arguments", className);
                            callback.onResponse(
                                    new Exception(className + " constructor needs arguments"),
                                    null);
                            return;
                        }

                        ABIObject constructorABIObject =
                                ABIObjectFactory.createInputObject(constructor);
                        try {
                            ABIObject abiObject =
                                    abiCodecJsonWrapper.encode(constructorABIObject, finalParams);
                            paramsABI = abiObject.encode();
                            if (logger.isDebugEnabled()) {
                                logger.debug(
                                        " className: {}, params: {}, abi: {}",
                                        className,
                                        finalParams.toArray(new String[0]),
                                        paramsABI);
                            }
                        } catch (Exception e) {
                            logger.error(
                                    "{} constructor arguments encode failed, params: {}, e: ",
                                    className,
                                    finalParams.toArray(new String[0]),
                                    e);
                            callback.onResponse(
                                    new Exception(
                                            className
                                                    + " constructor arguments encode failed, e: "
                                                    + e.getMessage()),
                                    null);
                            return;
                        }
                    }

                    if (logger.isTraceEnabled()) {
                        logger.trace(
                                "deploy contract, name: {}, bin: {}, abi:{}",
                                cnsName,
                                metadata.bin,
                                metadata.abi);
                    }

                    // deploy contract
                    deployContract(
                            groupID,
                            chainID,
                            cnsName,
                            metadata.bin + paramsABI,
                            credentials,
                            blockHeaderManager,
                            connection,
                            (deployException, address) -> {
                                if (Objects.nonNull(deployException)) {
                                    callback.onResponse(
                                            new Exception(
                                                    "deploying contract failed, "
                                                            + deployException.getMessage()),
                                            null);
                                    return;
                                }

                                // register cns
                                asyncCnsService.insert(
                                        cnsName,
                                        address,
                                        version,
                                        metadata.abi,
                                        account,
                                        blockHeaderManager,
                                        connection,
                                        insertException -> {
                                            if (Objects.nonNull(insertException)) {
                                                callback.onResponse(
                                                        new Exception(
                                                                "registering cns failed: "
                                                                        + insertException
                                                                                .getMessage()),
                                                        null);
                                                return;
                                            }

                                            callback.onResponse(null, address);
                                        });
                            });
                });
    }

    private interface CheckContractVersionCallback {
        void onResponse(Exception e);
    }

    private void checkContractVersion(
            String name,
            String version,
            Connection connection,
            CheckContractVersionCallback callback) {

        asyncCnsService.selectByNameAndVersion(
                name,
                version,
                connection,
                (exception, infoList) -> {
                    if (Objects.nonNull(exception)) {
                        callback.onResponse(exception);
                        return;
                    }

                    if (Objects.nonNull(infoList) && !infoList.isEmpty()) {
                        callback.onResponse(
                                new Exception("contract name and version already exist"));
                    } else {
                        callback.onResponse(null);
                    }
                });
    }

    private interface DeployContractCallback {
        void onResponse(Exception e, String address);
    }

    private void deployContract(
            int groupID,
            int chainID,
            String name,
            String bin,
            Credentials credentials,
            BlockHeaderManager blockHeaderManager,
            Connection connection,
            DeployContractCallback callback) {
        blockHeaderManager.asyncGetBlockNumber(
                (exception, blockNumber) -> {
                    if (Objects.nonNull(exception)) {
                        callback.onResponse(
                                new Exception(
                                        "getting block number failed, " + exception.getMessage()),
                                null);
                        return;
                    }

                    // get signed transaction hex string
                    String signTx =
                            SignTransaction.sign(
                                    credentials,
                                    null,
                                    BigInteger.valueOf(groupID),
                                    BigInteger.valueOf(chainID),
                                    BigInteger.valueOf(blockNumber),
                                    bin);

                    TransactionRequest transactionRequest = new TransactionRequest();
                    transactionRequest.setMethod(BCOSConstant.METHOD_DEPLOY);
                    TransactionParams transaction =
                            new TransactionParams(
                                    transactionRequest, signTx, TransactionParams.TP_YPE.DEPLOY);
                    Request request;
                    try {
                        request =
                                RequestFactory.requestBuilder(
                                        BCOSRequestType.SEND_TRANSACTION,
                                        objectMapper.writeValueAsBytes(transaction));
                    } catch (Exception e) {
                        logger.warn("exception occurs", e);
                        callback.onResponse(e, null);
                        return;
                    }

                    // sendTransaction
                    connection.asyncSend(
                            request,
                            deployResponse -> {
                                try {
                                    if (deployResponse.getErrorCode() != BCOSStatusCode.Success) {
                                        callback.onResponse(
                                                new Exception(deployResponse.getErrorMessage()),
                                                null);
                                        return;
                                    }

                                    TransactionReceipt receipt =
                                            objectMapper.readValue(
                                                    deployResponse.getData(),
                                                    TransactionReceipt.class);

                                    if (receipt.isStatusOK()) {
                                        blockHeaderManager.asyncGetBlockHeader(
                                                receipt.getBlockNumber().longValue(),
                                                (blockHeaderException, blockHeader) -> {
                                                    if (Objects.nonNull(blockHeaderException)) {
                                                        callback.onResponse(
                                                                new Exception(
                                                                        "getting block header failed, "
                                                                                + blockHeaderException
                                                                                        .getMessage()),
                                                                null);
                                                        return;
                                                    }
                                                    try {
                                                        MerkleValidation merkleValidation =
                                                                new MerkleValidation();
                                                        merkleValidation
                                                                .verifyTransactionReceiptProof(
                                                                        receipt.getBlockNumber()
                                                                                .longValue(),
                                                                        receipt
                                                                                .getTransactionHash(),
                                                                        blockHeader,
                                                                        receipt);

                                                        // save address if it is proxy contract
                                                        if (BCOSConstant.BCOS_PROXY_NAME.equals(
                                                                name)) {
                                                            Map<String, String> properties =
                                                                    connection.getProperties();
                                                            properties.put(
                                                                    BCOSConstant.BCOS_PROXY_NAME,
                                                                    receipt.getContractAddress());
                                                        }

                                                        callback.onResponse(
                                                                null, receipt.getContractAddress());
                                                    } catch (BCOSStubException e) {
                                                        logger.warn(
                                                                "verifying transaction of deploy failed",
                                                                e);
                                                        callback.onResponse(
                                                                new Exception(
                                                                        "verifying transaction of deploy failed, "
                                                                                + e.getMessage()),
                                                                null);
                                                    }
                                                });
                                    } else {
                                        callback.onResponse(
                                                new Exception(receipt.getMessage()), null);
                                    }
                                } catch (Exception e) {
                                    logger.warn("exception occurs", e);
                                    callback.onResponse(e, null);
                                }
                            });
                });
    }
}
