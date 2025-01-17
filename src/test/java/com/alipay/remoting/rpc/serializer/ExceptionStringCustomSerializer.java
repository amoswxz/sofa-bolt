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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * a String serializer throw exception
 *
 * @author xiaomin.cxm
 * @version $Id: NormalStringCustomSerializer.java, v 0.1 Apr 11, 2016 10:18:59 PM xiaomin.cxm Exp $
 */
public class ExceptionStringCustomSerializer extends DefaultCustomSerializer {

    private AtomicBoolean serialFlag = new AtomicBoolean();
    private AtomicBoolean deserialFlag = new AtomicBoolean();
    private boolean serialException = false;
    private boolean serialRuntimeException = true;
    private boolean deserialException = false;
    private boolean deserialRuntimeException = true;

    public ExceptionStringCustomSerializer(boolean serialException, boolean deserialException) {
        this.serialException = serialException;
        this.deserialException = deserialException;
    }

    public ExceptionStringCustomSerializer(boolean serialException, boolean serialRuntimeException,
            boolean deserialException,
            boolean deserialRuntimeException) {
        this.serialException = serialException;
        this.serialRuntimeException = serialRuntimeException;
        this.deserialException = deserialException;
        this.deserialRuntimeException = deserialRuntimeException;
    }

    /**
     * @see CustomSerializer#serializeContent(ResponseCommand)
     */
    @Override
    public <T extends ResponseCommand> boolean serializeContent(T response)
            throws SerializationException {
        serialFlag.set(true);
        if (serialRuntimeException) {
            throw new RuntimeException("serialRuntimeException in ExceptionStringCustomSerializer!");
        } else if (serialException) {
            throw new SerializationException("serialException in ExceptionStringCustomSerializer!");
        } else {
            return false;// use default codec 
        }
    }

    /**
     * @see CustomSerializer#deserializeContent(ResponseCommand, InvokeContext)
     */
    @Override
    public <T extends ResponseCommand> boolean deserializeContent(T response,
            InvokeContext invokeContext)
            throws DeserializationException {
        deserialFlag.set(true);
        if (deserialRuntimeException) {
            throw new RuntimeException(
                    "deserialRuntimeException in ExceptionStringCustomSerializer!");
        } else if (deserialException) {
            throw new DeserializationException(
                    "deserialException in ExceptionStringCustomSerializer!");
        } else {
            return false;// use default codec 
        }
    }

    public boolean isSerialized() {
        return this.serialFlag.get();
    }

    public boolean isDeserialized() {
        return this.deserialFlag.get();
    }
}
