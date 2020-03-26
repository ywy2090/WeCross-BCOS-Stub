package com.webank.wecross.stub.bcos.web3j;

import org.fisco.bcos.web3j.protocol.Web3jService;
import org.fisco.bcos.web3j.protocol.core.JsonRpc2_0Web3j;
import org.fisco.bcos.web3j.protocol.core.Request;
import org.fisco.bcos.web3j.protocol.core.methods.response.SendTransaction;

public class Web3jMock extends JsonRpc2_0Web3j {
    public Web3jMock(Web3jService web3jService) {
        super(web3jService);
    }

    public Web3jMock(Web3jService web3jService, int groupId) {
        super(web3jService, groupId);
    }

    @Override
    public Request<?, SendTransaction> sendRawTransaction(String signedTransactionData) {
        return super.sendRawTransaction(signedTransactionData);
    }
}
