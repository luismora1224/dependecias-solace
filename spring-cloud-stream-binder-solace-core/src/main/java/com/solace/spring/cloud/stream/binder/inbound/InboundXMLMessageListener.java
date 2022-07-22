package com.solace.spring.cloud.stream.binder.inbound;

import com.solace.spring.cloud.stream.binder.util.SolaceMessageConversionException;
import com.solace.spring.cloud.stream.binder.util.SolaceMessageHeaderErrorMessageStrategy;
import com.solace.spring.cloud.stream.binder.util.XMLMessageMapper;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.XMLMessage;
import com.solacesystems.jcsmp.XMLMessageListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.core.AttributeAccessor;
import org.springframework.integration.support.AckUtils;
import org.springframework.integration.support.ErrorMessageUtils;
import org.springframework.integration.support.StaticMessageHeaderAccessor;
import org.springframework.messaging.Message;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

class InboundXMLMessageListener implements XMLMessageListener {
	final ConsumerDestination consumerDestination;
	final ThreadLocal<AttributeAccessor> attributesHolder;
	private final XMLMessageMapper xmlMessageMapper = new XMLMessageMapper();
	private final Consumer<Message<?>> messageConsumer;
	private final Function<RuntimeException,Boolean> errorHandlerFunction;
	private final FlowConnectionScheduler flowConnectionScheduler;
	private final Consumer<FlowReceiver> flowReconnectConsumer;
	private final boolean needHolder;
	private final boolean needAttributes;

	private Future<FlowReceiver> futureFlowReceiver;

	private static final Log logger = LogFactory.getLog(InboundXMLMessageListener.class);

	InboundXMLMessageListener(ConsumerDestination consumerDestination,
							  Consumer<Message<?>> messageConsumer,
							  FlowConnectionScheduler flowConnectionScheduler,
							  Consumer<FlowReceiver> flowReconnectConsumer,
							  Function<RuntimeException,Boolean> errorHandlerFunction,
							  ThreadLocal<AttributeAccessor> attributesHolder,
							  boolean needHolderAndAttributes) {
		this(consumerDestination, messageConsumer, flowConnectionScheduler, flowReconnectConsumer, errorHandlerFunction,
				attributesHolder, needHolderAndAttributes, needHolderAndAttributes);
	}

	InboundXMLMessageListener(ConsumerDestination consumerDestination,
							  Consumer<Message<?>> messageConsumer,
							  FlowConnectionScheduler flowConnectionScheduler,
							  Consumer<FlowReceiver> flowReconnectConsumer,
							  Function<RuntimeException,Boolean> errorHandlerFunction,
							  ThreadLocal<AttributeAccessor> attributesHolder,
							  boolean needHolder,
							  boolean needAttributes) {
		this.consumerDestination = consumerDestination;
		this.messageConsumer = messageConsumer;
		this.errorHandlerFunction = errorHandlerFunction;
		this.flowConnectionScheduler = flowConnectionScheduler;
		this.flowReconnectConsumer = flowReconnectConsumer;
		this.attributesHolder = attributesHolder;
		this.needHolder = needHolder;
		this.needAttributes = needAttributes;
	}

	@Override
	public void onReceive(BytesXMLMessage bytesXMLMessage) {
		final Message<?> message;
		try {
			message = xmlMessageMapper.map(bytesXMLMessage);
			handleMessage(message, bytesXMLMessage);
		} catch (SolaceMessageConversionException e) {
			handleError(e, bytesXMLMessage, bytesXMLMessage::ackMessage);
		} finally {
			if (needHolder) {
				attributesHolder.remove();
			}
		}
	}

	void handleMessage(final Message<?> message, BytesXMLMessage bytesXMLMessage) {
		try {
			sendToConsumer(message, bytesXMLMessage);
			AckUtils.autoAck(StaticMessageHeaderAccessor.getAcknowledgmentCallback(message));
		} catch (RuntimeException e) {
			handleError(e, bytesXMLMessage, () -> AckUtils.autoNack(StaticMessageHeaderAccessor.getAcknowledgmentCallback(message)));
		}
	}

	void sendToConsumer(final Message<?> message, final BytesXMLMessage bytesXMLMessage) throws RuntimeException {
		setAttributesIfNecessary(bytesXMLMessage, message);
		AtomicInteger deliveryAttempt = StaticMessageHeaderAccessor.getDeliveryAttempt(message);
		if (deliveryAttempt != null) {
			deliveryAttempt.incrementAndGet();
		}
		messageConsumer.accept(message);
	}

	@Override
	public void onException(JCSMPException e) {
		if (futureFlowReceiver == null && flowConnectionScheduler.isShutdownException(e)) {
			logger.warn(String.format("%s has disconnected from queue %s",
					FlowReceiver.class.getSimpleName(), consumerDestination.getName()));
			futureFlowReceiver = flowConnectionScheduler.createFutureFlow(this);
		}

		if (futureFlowReceiver != null && futureFlowReceiver.isDone()) {
			try {
				flowReconnectConsumer.accept(futureFlowReceiver.get());
				futureFlowReceiver = null;
			} catch (ExecutionException e1) {
				throw new RuntimeException(e1.getCause());
			} catch (InterruptedException ignored) {}
		}
	}

	void setAttributesIfNecessary(XMLMessage xmlMessage, org.springframework.messaging.Message<?> message) {
		if (needHolder) {
			attributesHolder.set(ErrorMessageUtils.getAttributeAccessor(null, null));
		}

		if (needAttributes) {
			AttributeAccessor attributes = attributesHolder.get();
			if (attributes != null) {
				attributes.setAttribute(SolaceMessageHeaderErrorMessageStrategy.INPUT_MESSAGE, message);
				attributes.setAttribute(SolaceMessageHeaderErrorMessageStrategy.SOLACE_RAW_MESSAGE, xmlMessage);
			}
		}
	}

	private void handleError(RuntimeException e, BytesXMLMessage bytesXMLMessage, Runnable acknowledgement) {
		setAttributesIfNecessary(bytesXMLMessage, null);
		boolean wasProcessedByErrorHandler = errorHandlerFunction != null && errorHandlerFunction.apply(e);
		acknowledgement.run();
		if (!wasProcessedByErrorHandler) throw e;
	}
}
