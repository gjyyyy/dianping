package com.hmdp.service.receiver;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.MqConst;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class OrderReceiver {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(bindings = @QueueBinding(
            value =@Queue(value = MqConst.ORDER_QUEUE),
            exchange = @Exchange(value = MqConst.ORDER_EXCHANGE),
            key = {MqConst.ORDER_ROUTINGKEY}
    ))
    public void orderHandler(VoucherOrder voucherOrder){
        voucherOrderService.createVoucherOrder(voucherOrder);
    }
}
