/*
 * Copyright (C) 2020 Graylog, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Server Side Public License for more details.
 *
 * You should have received a copy of the Server Side Public License
 * along with this program. If not, see
 * <http://www.mongodb.com/licensing/server-side-public-license>.
 */

package org.graylog2.inputs.transports.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;

/**
 * KafkaTransport单元测试类
 *
 * @author 杨振亮
 * @date 2022/8/30 11:34
 */
public class KafkaTransportTest {

    @Test
    public void testConsumerRecordsEncodeAsBytes() throws IOException {
        String topic = "caiji-site-article";
        int partition = 6;
        long offset = 111111;
        byte[] key = "article".getBytes("utf-8");
        key = null;
        byte[] values = "{\"site_name\":\"济宁交通信息网\",\"entrance_url\":\"http://jnjt.jining.gov.cn/\",\"request_time\":1661830945332,\"http_code\":0,\"target_ip\":\"240e:c3:2002:5:0:0:0:35\",\"ip\":\"192.168.2.113\",\"biz_type\":16,\"site_code\":\"3708000012\",\"request_url\":\"http://www.jxlcx.gov.cn/module/xxgk/search.jsp?texttype=&fbtime=&vc_all=&vc_filenumber=&vc_title=&vc_number=&currpage=27&sortfield=0&fields=&fieldConfigId=&hasNoPages=&infoCount=&area=LCX0008&currpage=27&currpage=27&divid=680&fbtime=&fbtime=&fieldConfigId=&fields=&hasNoPages=&infoCount=&infotypeId=&jdid=3&sortfield=0&texttype=&texttype=&vc_all=&vc_all=&vc_filenumber=&vc_filenumber=&vc_number=&vc_number=&vc_title=&vc_title=\"}".getBytes("UTF-8");
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord(topic, partition, offset, key
                , values);

        //使用jackson追加kafka元数据
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000000; i++) {
            long timestamp = System.currentTimeMillis();
            ObjectMapper objectMapper = new ObjectMapper();
            HashMap hashMap = objectMapper.readValue(values, HashMap.class);
            hashMap.put("topic", topic);
            hashMap.put("partition", partition);
            hashMap.put("offset", offset);
            hashMap.put("timestamp", timestamp);
            byte[] bytes = objectMapper.writeValueAsBytes(hashMap);
        }
        long end = System.currentTimeMillis();
        System.out.println("ObjectMapper1000000数据花费时间:" + (end - start) + "ms");


        //使用java native方法System.arraycopy连接byte array
        start = System.currentTimeMillis();
        for (int i = 0; i < 1000000; i++) {
            long timestamp = System.currentTimeMillis();
            String kafkaMeta = ",\"topic\":\"" + topic + "\",\"partition\":" + partition + ",\"offset\":" + offset + ",\"timestamp\":" + timestamp;
            if (record.key() != null) {
                String _key = new String(record.key(), "UTF-8");
                kafkaMeta += ",\"key\":\"" + _key + "\"";
            }
            kafkaMeta += "}";

            byte[] kafkaMetaByteArray = kafkaMeta.getBytes("utf-8");
            byte[] result = new byte[values.length - 1 + kafkaMetaByteArray.length];
            System.arraycopy(values, 0, result, 0, values.length - 1);
            System.arraycopy(kafkaMetaByteArray, 0, result, values.length - 1, kafkaMetaByteArray.length);
        }
        end = System.currentTimeMillis();
        System.out.println("System.arraycopy1000000数据花费时间:" + (end - start) + "ms");


    }
}
