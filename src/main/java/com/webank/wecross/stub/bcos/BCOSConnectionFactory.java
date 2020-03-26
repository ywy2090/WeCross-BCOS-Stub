package com.webank.wecross.stub.bcos;

import com.webank.wecross.stub.bcos.config.BCOSStubConfig;
import com.webank.wecross.stub.bcos.config.BCOSStubConfigParser;
import com.webank.wecross.stub.bcos.web3j.Web3jUtility;
import com.webank.wecross.stub.bcos.web3j.Web3jWrapper;
import com.webank.wecross.stub.bcos.web3j.Web3jWrapperImpl;
import java.io.IOException;
import org.fisco.bcos.web3j.protocol.Web3j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BCOSConnectionFactory {
    private static Logger logger = LoggerFactory.getLogger(BCOSConnectionFactory.class);

    private BCOSConnectionFactory() {}

    public static BCOSConnection build(String stubConfigPath) throws Exception {
        /** load stub.toml config */
        logger.info(" stubConfigPath: {} ", stubConfigPath);
        BCOSStubConfigParser loader = new BCOSStubConfigParser(stubConfigPath);
        BCOSStubConfig bcosStubConfig = loader.loadConfig();

        Web3j web3j = Web3jUtility.initWeb3j(bcosStubConfig.getChannelService());
        Web3jWrapperImpl web3jWrapper1 = new Web3jWrapperImpl(web3j);

        BCOSConnection bcosConnection = new BCOSConnection(web3jWrapper1);

        bcosConnection.setResourceInfoList(
                bcosConnection.getResourceInfoList(bcosStubConfig.getResources()));
        return bcosConnection;
    }

    public static BCOSConnection build(String stubConfigPath, Web3jWrapper web3jWrapper)
            throws IOException {
        /** load stub.toml config */
        logger.info(" stubConfigPath: {}", stubConfigPath);
        BCOSStubConfigParser loader = new BCOSStubConfigParser(stubConfigPath);
        BCOSStubConfig bcosStubConfig = loader.loadConfig();

        BCOSConnection bcosConnection = new BCOSConnection(web3jWrapper);

        bcosConnection.setResourceInfoList(
                bcosConnection.getResourceInfoList(bcosStubConfig.getResources()));
        return bcosConnection;
    }
}
