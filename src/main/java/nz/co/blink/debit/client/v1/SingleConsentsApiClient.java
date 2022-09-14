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

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import nz.co.blink.debit.dto.v1.Amount;
import nz.co.blink.debit.dto.v1.AuthFlow;
import nz.co.blink.debit.dto.v1.AuthFlowDetail;
import nz.co.blink.debit.dto.v1.Bank;
import nz.co.blink.debit.dto.v1.Consent;
import nz.co.blink.debit.dto.v1.ConsentDetail;
import nz.co.blink.debit.dto.v1.CreateConsentResponse;
import nz.co.blink.debit.dto.v1.DecoupledFlow;
import nz.co.blink.debit.dto.v1.DecoupledFlowHint;
import nz.co.blink.debit.dto.v1.FlowHint;
import nz.co.blink.debit.dto.v1.GatewayFlow;
import nz.co.blink.debit.dto.v1.IdentifierType;
import nz.co.blink.debit.dto.v1.OneOfauthFlowDetail;
import nz.co.blink.debit.dto.v1.Pcr;
import nz.co.blink.debit.dto.v1.RedirectFlow;
import nz.co.blink.debit.dto.v1.RedirectFlowHint;
import nz.co.blink.debit.dto.v1.SingleConsentRequest;
import nz.co.blink.debit.exception.ExpiredAccessTokenException;
import nz.co.blink.debit.helpers.ResponseHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.UUID;

import static nz.co.blink.debit.enums.BlinkDebitConstant.BEARER;
import static nz.co.blink.debit.enums.BlinkDebitConstant.INTERACTION_ID;
import static nz.co.blink.debit.enums.BlinkDebitConstant.REQUEST_ID;
import static nz.co.blink.debit.enums.BlinkDebitConstant.SINGLE_CONSENTS_PATH;

/**
 * The client for single consents.
 */
@Component
public class SingleConsentsApiClient {

    private final WebClient webClient;

    /**
     * Default constructor.
     *
     * @param webClient the {@link WebClient}
     */
    @Autowired
    public SingleConsentsApiClient(@Qualifier("blinkDebitWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Creates a single payment consent request with redirect flow.
     *
     * @param requestId       the correlation ID
     * @param accessToken     the OAuth2 access token
     * @param type            the {@link AuthFlowDetail.TypeEnum}
     * @param bank            the {@link Bank}
     * @param redirectUri     the redirect URI
     * @param particulars     the particulars
     * @param code            the code
     * @param reference       the reference
     * @param total           the total
     * @return the {@link CreateConsentResponse} {@link Mono}
     * @throws ExpiredAccessTokenException thrown when the access token has expired after 1 day
     */
    public Mono<CreateConsentResponse> createSingleConsent(final String requestId, final String accessToken,
                                                           AuthFlowDetail.TypeEnum type, Bank bank,
                                                           final String redirectUri, final String particulars,
                                                           final String code, final String reference, final String total)
            throws ExpiredAccessTokenException {
        return createSingleConsent(requestId, accessToken, type, bank, redirectUri, particulars, code, reference, total,
                null, null, null, null);
    }

    /**
     * Creates a single payment consent request with the bank that will go to the customer for approval.
     * A successful response does not indicate a completed consent.
     * The status of the consent can be subsequently checked with the consent ID.
     *
     * @param requestId       the correlation ID
     * @param accessToken     the OAuth2 access token
     * @param type            the {@link AuthFlowDetail.TypeEnum}
     * @param bank            the {@link Bank}
     * @param redirectUri     the redirect URI
     * @param particulars     the particulars
     * @param code            the code
     * @param reference       the reference
     * @param total           the total
     * @param flowHintType    the {@link FlowHint.TypeEnum} for gateway flow
     * @param identifierType  the {@link IdentifierType} for decoupled flow
     * @param identifierValue the identifier value for decoupled flow
     * @param callbackUrl     the merchant callback/webhook URL for decoupled flow
     * @return the {@link CreateConsentResponse} {@link Mono}
     * @throws ExpiredAccessTokenException thrown when the access token has expired after 1 day
     */
    public Mono<CreateConsentResponse> createSingleConsent(final String requestId, final String accessToken,
                                                           AuthFlowDetail.TypeEnum type, Bank bank,
                                                           final String redirectUri, final String particulars,
                                                           final String code, final String reference, final String total,
                                                           FlowHint.TypeEnum flowHintType,
                                                           IdentifierType identifierType, final String identifierValue,
                                                           final String callbackUrl)
            throws ExpiredAccessTokenException {
        FlowHint flowHint = null;
        if (flowHintType != null) {
            switch (flowHintType) {
                case REDIRECT:
                    flowHint = new RedirectFlowHint()
                            .bank(bank)
                            .type(flowHintType);
                    break;
                case DECOUPLED:
                    flowHint = new DecoupledFlowHint()
                            .identifierType(identifierType)
                            .identifierValue(identifierValue)
                            .bank(bank)
                            .type(flowHintType);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported flow hint type: " + flowHintType);
            }
        }

        OneOfauthFlowDetail detail;
        switch (type) {
            case REDIRECT:
                detail = (OneOfauthFlowDetail) new RedirectFlow()
                        .bank(bank)
                        .redirectUri(redirectUri)
                        .type(type);
                break;
            case DECOUPLED:
                detail = (OneOfauthFlowDetail) new DecoupledFlow()
                        .bank(bank)
                        .callbackUrl(callbackUrl)
                        .identifierType(identifierType)
                        .identifierValue(identifierValue)
                        .type(type);
                break;
            case GATEWAY:
                detail = (OneOfauthFlowDetail) new GatewayFlow()
                        .flowHint(flowHint)
                        .redirectUri(redirectUri)
                        .type(type);
                break;
            default:
                throw new IllegalArgumentException("Unsupported authorisation flow detail type: " + type);
        }

        SingleConsentRequest request = (SingleConsentRequest) new SingleConsentRequest()
                .flow(new AuthFlow()
                        .detail(detail))
                .pcr(new Pcr()
                        .particulars(StringUtils.truncate(particulars, 20))
                        .code(StringUtils.truncate(code, 20))
                        .reference(StringUtils.truncate(reference, 20)))
                .amount(new Amount()
                        .currency(Amount.CurrencyEnum.NZD)
                        .total(total))
                .type(ConsentDetail.TypeEnum.SINGLE);

        DecodedJWT jwt = JWT.decode(accessToken);
        if (jwt.getExpiresAt().before(new Date())) {
            throw new ExpiredAccessTokenException();
        }

        String authorization = BEARER.getValue() + accessToken;

        return webClient
                .post()
                .uri(SINGLE_CONSENTS_PATH.getValue())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(httpHeaders -> {
                    httpHeaders.add(REQUEST_ID.getValue(), requestId);
                    httpHeaders.add(HttpHeaders.AUTHORIZATION, authorization);
                    httpHeaders.add(INTERACTION_ID.getValue(), requestId);
                })
                .bodyValue(request)
                .exchangeToMono(ResponseHandler.getResponseMono(CreateConsentResponse.class));
    }

    /**
     * Retrieves an existing consent by ID.
     *
     * @param requestId   the correlation ID
     * @param accessToken the OAuth2 access token
     * @param consentId   the consent ID
     * @return the {@link Consent} {@link Mono}
     * @throws ExpiredAccessTokenException thrown when the access token has expired after 1 day
     */
    public Mono<Consent> getSingleConsent(final String requestId, final String accessToken, UUID consentId)
            throws ExpiredAccessTokenException {
        DecodedJWT jwt = JWT.decode(accessToken);
        if (jwt.getExpiresAt().before(new Date())) {
            throw new ExpiredAccessTokenException();
        }

        String authorization = BEARER.getValue() + accessToken;

        return webClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(SINGLE_CONSENTS_PATH.getValue() + "/{consentId}")
                        .build(consentId))
                .accept(MediaType.APPLICATION_JSON)
                .headers(httpHeaders -> {
                    httpHeaders.add(REQUEST_ID.getValue(), requestId);
                    httpHeaders.add(HttpHeaders.AUTHORIZATION, authorization);
                    httpHeaders.add(INTERACTION_ID.getValue(), requestId);
                })
                .exchangeToMono(ResponseHandler.getResponseMono(Consent.class));
    }

    /**
     * Revokes an existing consent by ID.
     *
     * @param requestId   the correlation ID
     * @param accessToken the OAuth2 access token
     * @param consentId   the consent ID
     * @throws ExpiredAccessTokenException thrown when the access token has expired after 1 day
     */
    public Mono<Void> revokeSingleConsent(final String requestId, final String accessToken, UUID consentId)
            throws ExpiredAccessTokenException {
        DecodedJWT jwt = JWT.decode(accessToken);
        if (jwt.getExpiresAt().before(new Date())) {
            throw new ExpiredAccessTokenException();
        }

        String authorization = BEARER.getValue() + accessToken;

        return webClient
                .delete()
                .uri(uriBuilder -> uriBuilder
                        .path(SINGLE_CONSENTS_PATH.getValue() + "/{consentId}")
                        .build(consentId))
                .accept(MediaType.APPLICATION_JSON)
                .headers(httpHeaders -> {
                    httpHeaders.add(REQUEST_ID.getValue(), requestId);
                    httpHeaders.add(HttpHeaders.AUTHORIZATION, authorization);
                    httpHeaders.add(INTERACTION_ID.getValue(), requestId);
                })
                .exchangeToMono(ResponseHandler.getResponseMono(Void.class));
    }
}
