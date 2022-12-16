/**
 * Copyright (c) 2022 BlinkPay
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package nz.co.blink.debit.client.v1;

import lombok.extern.slf4j.Slf4j;
import nz.co.blink.debit.dto.v1.FullRefundRequest;
import nz.co.blink.debit.dto.v1.PartialRefundRequest;
import nz.co.blink.debit.dto.v1.Payment;
import nz.co.blink.debit.dto.v1.Pcr;
import nz.co.blink.debit.dto.v1.Refund;
import nz.co.blink.debit.dto.v1.RefundDetail;
import nz.co.blink.debit.dto.v1.RefundResponse;
import nz.co.blink.debit.helpers.AccessTokenHandler;
import nz.co.blink.debit.helpers.ResponseHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static nz.co.blink.debit.enums.BlinkDebitConstant.REFUNDS_PATH;
import static nz.co.blink.debit.enums.BlinkDebitConstant.REQUEST_ID;

/**
 * The client for refunds.
 */
@Component
@Slf4j
public class RefundsApiClient {

    private final ReactorClientHttpConnector connector;

    private final String debitUrl;

    private final AccessTokenHandler accessTokenHandler;

    private final Validator validator;

    private WebClient.Builder webClientBuilder;

    /**
     * Default constructor.
     *
     * @param connector          the {@link ReactorClientHttpConnector}
     * @param debitUrl           the Blink Debit URL
     * @param accessTokenHandler the {@link AccessTokenHandler}
     * @param validator          the {@link Validator}
     */
    @Autowired
    public RefundsApiClient(@Qualifier("blinkDebitClientHttpConnector") ReactorClientHttpConnector connector,
                            @Value("${blinkpay.debit.url:}") final String debitUrl,
                            AccessTokenHandler accessTokenHandler, Validator validator) {
        this.connector = connector;
        this.debitUrl = debitUrl;
        this.accessTokenHandler = accessTokenHandler;
        this.validator = validator;
    }

    /**
     * Creates a refund.
     *
     * @param request the {@link PartialRefundRequest}
     * @return the {@link RefundResponse} {@link Mono}
     */
    public Mono<RefundResponse> createRefund(RefundDetail request) {
        return createRefund(request, null);
    }

    /**
     * Creates a refund.
     *
     * @param request   the {@link RefundDetail}
     * @param requestId the optional correlation ID
     * @return the {@link RefundResponse} {@link Mono}
     */
    public Mono<RefundResponse> createRefund(RefundDetail request, final String requestId) {
        if (request == null) {
            throw new IllegalArgumentException("Refund request must not be null");
        }

        if (request.getPaymentId() == null) {
            throw new IllegalArgumentException("Payment ID must not be null");
        }

        if (request instanceof PartialRefundRequest) {
            PartialRefundRequest partialRefundRequest = (PartialRefundRequest) request;

            validatePcr(partialRefundRequest.getPcr());

            if (partialRefundRequest.getAmount() == null) {
                throw new IllegalArgumentException("Amount must not be null");
            }

            if (partialRefundRequest.getAmount().getCurrency() == null) {
                throw new IllegalArgumentException("Currency must not be null");
            }
        } else if (request instanceof FullRefundRequest) {
            FullRefundRequest fullRefundRequest = (FullRefundRequest) request;

            validatePcr(fullRefundRequest.getPcr());
        }

        Set<ConstraintViolation<RefundDetail>> violations = new HashSet<>(validator.validate(request));
        if (!violations.isEmpty()) {
            String constraintViolations = violations.stream()
                    .map(cv -> cv == null ? "null" : cv.getPropertyPath() + ": " + cv.getMessage())
                    .collect(Collectors.joining(", "));
            log.error("Validation failed for refund request: {}", constraintViolations);
            throw new ConstraintViolationException("Validation failed for refund request", violations);
        }

        return createRefundMono(request, requestId);
    }

    /**
     * Retrieves an existing refund by ID.
     *
     * @param refundId the refund ID
     * @return the {@link Payment} {@link Mono}
     */
    public Mono<Refund> getRefund(UUID refundId) {
        return getRefund(refundId, null);
    }

    /**
     * Retrieves an existing refund by ID.
     *
     * @param refundId  the refund ID
     * @param requestId the optional correlation ID
     * @return the {@link Payment} {@link Mono}
     */
    public Mono<Refund> getRefund(UUID refundId, final String requestId) {
        if (refundId == null) {
            throw new IllegalArgumentException("Refund ID must not be null");
        }

        String correlationId = StringUtils.defaultIfBlank(requestId, UUID.randomUUID().toString());

        return getWebClientBuilder(correlationId)
                .build()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(REFUNDS_PATH.getValue() + "/{refundId}")
                        .build(refundId))
                .accept(MediaType.APPLICATION_JSON)
                .headers(httpHeaders -> httpHeaders.add(REQUEST_ID.getValue(), correlationId))
                .exchangeToMono(ResponseHandler.getResponseMono(Refund.class));
    }

    private Mono<RefundResponse> createRefundMono(RefundDetail request, String requestId) {
        String correlationId = StringUtils.defaultIfBlank(requestId, UUID.randomUUID().toString());

        return getWebClientBuilder(correlationId)
                .build()
                .post()
                .uri(REFUNDS_PATH.getValue())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(httpHeaders -> httpHeaders.add(REQUEST_ID.getValue(), correlationId))
                .bodyValue(request)
                .exchangeToMono(ResponseHandler.getResponseMono(RefundResponse.class));
    }

    private WebClient.Builder getWebClientBuilder(String requestId) {
        if (webClientBuilder != null) {
            return webClientBuilder;
        }

        return WebClient.builder()
                .clientConnector(connector)
                .defaultHeader(HttpHeaders.USER_AGENT, "Java/Blink SDK 1.0")
                .baseUrl(debitUrl)
                .filter(accessTokenHandler.setAccessToken(requestId));
    }

    private static void validatePcr(Pcr pcr) {
        if (pcr == null) {
            throw new IllegalArgumentException("PCR must not be null");
        }

        if (StringUtils.isBlank(pcr.getParticulars())) {
            throw new IllegalArgumentException("Particulars must have at least 1 character");
        }

        if (StringUtils.length(pcr.getParticulars()) > 12
                || StringUtils.length(pcr.getCode()) > 12
                || StringUtils.length(pcr.getReference()) > 12) {
            throw new IllegalArgumentException("PCR must not exceed 12 characters");
        }
    }
}
