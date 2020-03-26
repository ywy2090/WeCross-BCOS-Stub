package com.webank.wecross.stub.bcos;

import static junit.framework.TestCase.assertTrue;

import com.webank.wecross.stub.Driver;
import java.util.Objects;
import org.junit.Test;

public class BCOSStubFactoryTest {

    private BCOSStubFactory stubFactory = new BCOSStubFactory();

    @Test
    public void newDriverTest() {
        Driver driver = stubFactory.newDriver();
        assertTrue(Objects.nonNull(driver));
        assertTrue(driver instanceof BCOSDriver);
    }

    //    @Test
    //    public void newAccountTest() {
    //        Account account = stubFactory.newAccount("bcos", "accounts/bcos");
    //        assertTrue(Objects.nonNull(account));
    //        assertTrue(account instanceof BCOSAccount);
    //    }
}
