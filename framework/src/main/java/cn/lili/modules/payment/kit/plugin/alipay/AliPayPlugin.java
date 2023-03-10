package cn.lili.modules.payment.kit.plugin.alipay;

import cn.hutool.core.net.URLDecoder;
import cn.hutool.core.net.URLEncoder;
import cn.hutool.json.JSONUtil;
import cn.lili.common.context.ThreadContextHolder;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.enums.ResultUtil;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.ApiProperties;
import cn.lili.common.properties.DomainProperties;
import cn.lili.common.utils.BeanUtil;
import cn.lili.common.utils.SnowFlake;
import cn.lili.common.utils.StringUtils;
import cn.lili.common.vo.ResultMessage;
import cn.lili.modules.payment.entity.RefundLog;
import cn.lili.modules.payment.entity.enums.PaymentMethodEnum;
import cn.lili.modules.payment.kit.CashierSupport;
import cn.lili.modules.payment.kit.Payment;
import cn.lili.modules.payment.kit.dto.PayParam;
import cn.lili.modules.payment.kit.dto.PaymentSuccessParams;
import cn.lili.modules.payment.kit.params.dto.CashierParam;
import cn.lili.modules.payment.service.PaymentService;
import cn.lili.modules.payment.service.RefundLogService;
import cn.lili.modules.system.entity.dos.Setting;
import cn.lili.modules.system.entity.dto.payment.AlipayPaymentSetting;
import cn.lili.modules.system.entity.enums.SettingEnum;
import cn.lili.modules.system.service.SettingService;
import cn.lili.modules.wallet.entity.dos.MemberWithdrawApply;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.domain.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayFundTransUniTransferRequest;
import com.alipay.api.response.AlipayFundTransUniTransferResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * ???????????????
 *
 * @author Chopper
 * @since 2020/12/17 09:55
 */
@Slf4j
@Component
public class AliPayPlugin implements Payment {
    /**
     * ????????????
     */
    @Autowired
    private PaymentService paymentService;
    /**
     * ????????????
     */
    @Autowired
    private RefundLogService refundLogService;
    /**
     * ?????????
     */
    @Autowired
    private CashierSupport cashierSupport;
    /**
     * ??????
     */
    @Autowired
    private SettingService settingService;
    /**
     * API??????
     */
    @Autowired
    private ApiProperties apiProperties;
    /**
     * ????????????
     */
    @Autowired
    private DomainProperties domainProperties;

    @Override
    public ResultMessage<Object> h5pay(HttpServletRequest request, HttpServletResponse response, PayParam payParam) {

        CashierParam cashierParam = cashierSupport.cashierParam(payParam);
        //??????????????????
        String outTradeNo = SnowFlake.getIdStr();
        //??????????????????
        AlipayTradeWapPayModel payModel = new AlipayTradeWapPayModel();
        payModel.setBody(cashierParam.getTitle());
        payModel.setSubject(cashierParam.getDetail());
        payModel.setTotalAmount(cashierParam.getPrice() + "");
        //????????????
        payModel.setPassbackParams(URLEncoder.createAll().encode(BeanUtil.formatKeyValuePair(payParam), StandardCharsets.UTF_8));
        //3????????????
        payModel.setTimeoutExpress("3m");
        payModel.setOutTradeNo(outTradeNo);
        payModel.setProductCode("QUICK_WAP_PAY");
        try {
            log.info("?????????H5?????????{}", JSONUtil.toJsonStr(payModel));
            AliPayRequest.wapPay(response, payModel, callbackUrl(apiProperties.getBuyer(), PaymentMethodEnum.ALIPAY),
                    notifyUrl(apiProperties.getBuyer(), PaymentMethodEnum.ALIPAY));
        } catch (Exception e) {
            log.error("H5????????????", e);
            throw new ServiceException(ResultCode.ALIPAY_EXCEPTION);
        }
        return null;
    }


    @Override
    public ResultMessage<Object> jsApiPay(HttpServletRequest request, PayParam payParam) {
        throw new ServiceException(ResultCode.PAY_NOT_SUPPORT);
    }

    @Override
    public ResultMessage<Object> appPay(HttpServletRequest request, PayParam payParam) {
        try {

            CashierParam cashierParam = cashierSupport.cashierParam(payParam);
            //??????????????????
            String outTradeNo = SnowFlake.getIdStr();

            AlipayTradeAppPayModel payModel = new AlipayTradeAppPayModel();

            payModel.setBody(cashierParam.getTitle());
            payModel.setSubject(cashierParam.getDetail());
            payModel.setTotalAmount(cashierParam.getPrice() + "");

            //3????????????
            payModel.setTimeoutExpress("3m");
            //????????????
            payModel.setPassbackParams(URLEncoder.createAll().encode(BeanUtil.formatKeyValuePair(payParam), StandardCharsets.UTF_8));
            payModel.setOutTradeNo(outTradeNo);
            payModel.setProductCode("QUICK_MSECURITY_PAY");

            log.info("?????????APP?????????{}", payModel);
            String orderInfo = AliPayRequest.appPayToResponse(payModel, notifyUrl(apiProperties.getBuyer(), PaymentMethodEnum.ALIPAY)).getBody();
            log.info("?????????APP?????????????????????{}", orderInfo);
            return ResultUtil.data(orderInfo);
        } catch (AlipayApiException e) {
            log.error("????????????????????????", e);
            throw new ServiceException(ResultCode.ALIPAY_EXCEPTION);
        } catch (Exception e) {
            log.error("?????????????????????", e);
            throw new ServiceException(ResultCode.PAY_ERROR);
        }
    }

    @Override
    public ResultMessage<Object> nativePay(HttpServletRequest request, PayParam payParam) {

        try {
            CashierParam cashierParam = cashierSupport.cashierParam(payParam);

            AlipayTradePrecreateModel payModel = new AlipayTradePrecreateModel();

            //??????????????????
            String outTradeNo = SnowFlake.getIdStr();

            payModel.setBody(cashierParam.getTitle());
            payModel.setSubject(cashierParam.getDetail());
            payModel.setTotalAmount(cashierParam.getPrice() + "");

            //????????????
            payModel.setPassbackParams(URLEncoder.createAll().encode(BeanUtil.formatKeyValuePair(payParam), StandardCharsets.UTF_8));
            payModel.setTimeoutExpress("3m");
            payModel.setOutTradeNo(outTradeNo);
            log.info("??????????????????{}", payModel);
            String resultStr = AliPayRequest.tradePrecreatePayToResponse(payModel, notifyUrl(apiProperties.getBuyer(), PaymentMethodEnum.ALIPAY)).getBody();

            log.info("??????????????????????????????{}", resultStr);
            JSONObject jsonObject = JSONObject.parseObject(resultStr);
            return ResultUtil.data(jsonObject.getJSONObject("alipay_trade_precreate_response").getString("qr_code"));
        } catch (Exception e) {
            log.error("?????????????????????", e);
            throw new ServiceException(ResultCode.PAY_ERROR);
        }
    }


    @Override
    public void refund(RefundLog refundLog) {
        AlipayTradeRefundModel model = new AlipayTradeRefundModel();
        //???????????????????????????????????????
        if (StringUtils.isNotEmpty(refundLog.getPaymentReceivableNo())) {
            model.setTradeNo(refundLog.getPaymentReceivableNo());
        } else {
            throw new ServiceException(ResultCode.ALIPAY_PARAMS_EXCEPTION);
        }
        model.setRefundAmount(refundLog.getTotalAmount() + "");
        model.setRefundReason(refundLog.getRefundReason());
        model.setOutRequestNo(refundLog.getOutOrderNo());
        //????????????
        try {
            AlipayTradeRefundResponse alipayTradeRefundResponse = AliPayApi.tradeRefundToResponse(model);
            log.error("???????????????????????????{},??????????????????{}", JSONUtil.toJsonStr(model), JSONUtil.toJsonStr(alipayTradeRefundResponse));
            if (alipayTradeRefundResponse.isSuccess()) {
                refundLog.setIsRefund(true);
                refundLog.setReceivableNo(refundLog.getOutOrderNo());
            } else {
                refundLog.setErrorMessage(String.format("????????????%s,???????????????%s", alipayTradeRefundResponse.getSubCode(), alipayTradeRefundResponse.getSubMsg()));
            }
            refundLogService.save(refundLog);
        } catch (Exception e) {
            log.error("?????????????????????", e);
            throw new ServiceException(ResultCode.PAY_ERROR);
        }

    }

    @Override
    public void refundNotify(HttpServletRequest request) {
        //???????????????
    }

    @Override
    public void callBack(HttpServletRequest request) {
        log.info("?????????????????????");
        callback(request);

    }

    @Override
    public void notify(HttpServletRequest request) {
        verifyNotify(request);
        log.info("?????????????????????");
    }

    /**
     * ???????????????
     * ???????????????https://opendocs.alipay.com/open/02byuo?scene=ca56bca529e64125a2786703c6192d41&ref=api
     *
     * @param memberWithdrawApply ??????????????????
     */
    @Override
    public void transfer(MemberWithdrawApply memberWithdrawApply) {
        AlipayFundTransUniTransferModel model = new AlipayFundTransUniTransferModel();
        model.setOutBizNo(SnowFlake.createStr("T"));
        model.setRemark("????????????");
        model.setBusinessParams("{\"payer_show_name_use_alias\":\"true\"}");
        model.setBizScene("DIRECT_TRANSFER");
        Participant payeeInfo = new Participant();
        payeeInfo.setIdentity(memberWithdrawApply.getConnectNumber());
        payeeInfo.setIdentityType("ALIPAY_LOGON_ID");
        payeeInfo.setName(memberWithdrawApply.getRealName());
        model.setPayeeInfo(payeeInfo);

        model.setTransAmount(memberWithdrawApply.getApplyMoney().toString());
        model.setProductCode("TRANS_ACCOUNT_NO_PWD");
        model.setOrderTitle("????????????");
        //????????????
        try {
            AlipayFundTransUniTransferResponse alipayFundTransUniTransferResponse  = AliPayApi.uniTransferToResponse(model,null);
            log.error("???????????????????????????{},??????????????????{}", JSONUtil.toJsonStr(model), JSONUtil.toJsonStr(alipayFundTransUniTransferResponse));
            if (alipayFundTransUniTransferResponse.isSuccess()) {

            } else {
                log.error(alipayFundTransUniTransferResponse.getSubMsg());
            }
        } catch (Exception e) {
            log.error("?????????????????????", e);
            throw new ServiceException(ResultCode.PAY_ERROR);
        }
    }

    /**
     * ??????????????????
     *
     * @param request
     */
    private void callback(HttpServletRequest request) {
        try {
            AlipayPaymentSetting alipayPaymentSetting = alipayPaymentSetting();
            //???????????????????????????
            Map<String, String> map = AliPayApi.toMap(request);
            log.info("???????????????{}", JSONUtil.toJsonStr(map));
            boolean verifyResult = AlipaySignature.rsaCertCheckV1(map, alipayPaymentSetting.getAlipayPublicCertPath(), "UTF-8",
                    "RSA2");
            if (verifyResult) {
                log.info("?????????????????????????????????-?????????{}", map);
            } else {
                log.info("?????????????????????????????????-?????????{}", map);
            }

            ThreadContextHolder.getHttpResponse().sendRedirect(domainProperties.getWap() + "/pages/order/myOrder?status=0");
        } catch (Exception e) {
            log.error("??????????????????????????????", e);
        }

    }

    /**
     * ??????????????????
     *
     * @param request
     */
    private void verifyNotify(HttpServletRequest request) {
        try {
            AlipayPaymentSetting alipayPaymentSetting = alipayPaymentSetting();
            //???????????????????????????
            Map<String, String> map = AliPayApi.toMap(request);
            log.info("?????????????????????{}", JSONUtil.toJsonStr(map));
            boolean verifyResult = AlipaySignature.rsaCertCheckV1(map, alipayPaymentSetting.getAlipayPublicCertPath(), "UTF-8",
                    "RSA2");
            //??????????????????
            if (!"TRADE_FINISHED".equals(map.get("trade_status")) &&
                    !"TRADE_SUCCESS".equals(map.get("trade_status"))) {
                return;
            }
            String payParamStr = map.get("passback_params");
            String payParamJson = URLDecoder.decode(payParamStr, StandardCharsets.UTF_8);
            PayParam payParam = BeanUtil.formatKeyValuePair(payParamJson, new PayParam());


            if (verifyResult) {
                String tradeNo = map.get("trade_no");
                Double totalAmount = Double.parseDouble(map.get("total_amount"));
                PaymentSuccessParams paymentSuccessParams =
                        new PaymentSuccessParams(PaymentMethodEnum.ALIPAY.name(), tradeNo, totalAmount, payParam);

                paymentService.success(paymentSuccessParams);
                log.info("?????????????????????????????????-?????????{},????????????:{}", map, payParam);
            } else {
                log.info("?????????????????????????????????-?????????{}", map);
            }
        } catch (AlipayApiException e) {
            log.error("????????????????????????", e);
        }

    }

    /**
     * ????????????????????????
     *
     * @return
     */
    private AlipayPaymentSetting alipayPaymentSetting() {
        Setting setting = settingService.get(SettingEnum.ALIPAY_PAYMENT.name());
        if (setting != null) {
            return JSONUtil.toBean(setting.getSettingValue(), AlipayPaymentSetting.class);
        }
        throw new ServiceException(ResultCode.ALIPAY_NOT_SETTING);
    }


}