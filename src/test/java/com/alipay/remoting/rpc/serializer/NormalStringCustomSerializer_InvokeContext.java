/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.remoting.rpc.serializer;

import com.alipay.remoting.CustomSerializer;
import com.alipay.remoting.DefaultCustomSerializer;
import com.alipay.remoting.InvokeContext;
import com.alipay.remoting.exception.DeserializationException;
import com.alipay.remoting.exception.SerializationException;
import com.alipay.remoting.rpc.ResponseCommand;
import com.alipay.remoting.rpc.protocol.RpcResponseCommand;
import com.alipay.remoting.util.StringUtils;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * a custom serialize demo using invoke context
 *
 * @author xiaomin.cxm
 * @version $Id: NormalStringCustomSerializer_InvokeContext.java, v 0.1 Apr 11, 2016 10:18:59 PM xiaomin.cxm Exp $
 */
public class NormalStringCustomSerializer_InvokeContext extends DefaultCustomSerializer {

    public static final String UNIVERSAL_RESP = "UNIVERSAL RESPONSE";
    public static final String SERIALTYPE_KEY = "serial.type";
    public static final String SERIALTYPE1_value = "SERIAL1";
    public static final String SERIALTYPE2_value = "SERIAL2";
    private AtomicBoolean serialFlag = new AtomicBoolean();
    private AtomicBoolean deserialFlag = new AtomicBoolean();

    /**
     * @see CustomSerializer#serializeContent(ResponseCommand)
     */
    @Override
    public <T extends ResponseCommand> boolean serializeContent(T response)
            throws SerializationException {
        serialFlag.set(true);
        RpcResponseCommand rpcResp = (RpcResponseCommand) response;
        String str = (String) rpcResp.getResponseObject();
        try {
            rpcResp.setContent(str.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        return true;
    }

    /**
     * @see CustomSerializer#deserializeContent(ResponseCommand, InvokeContext)
     */
    @Override
    public <T extends ResponseCommand> boolean deserializeContent(T response,
            InvokeContext invokeContext)
            throws DeserializationException {
        deserialFlag.set(true);
        RpcResponseCommand rpcResp = (RpcResponseCommand) response;

        if (StringUtils.equals(SERIALTYPE1_value, (String) invokeContext.get(SERIALTYPE_KEY))) {
            try {
                rpcResp.setResponseObject(new String(rpcResp.getContent(), "UTF-8") + "RANDOM");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        } else {
            rpcResp.setResponseObject(UNIVERSAL_RESP);
        }
        return true;
    }

    public boolean isSerialized() {
        return this.serialFlag.get();
    }

    public boolean isDeserialized() {
        return this.deserialFlag.get();
    }
}
