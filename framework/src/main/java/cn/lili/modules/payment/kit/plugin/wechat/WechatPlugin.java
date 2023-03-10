package cn.lili.modules.payment.kit.plugin.wechat;

import cn.hutool.core.net.URLDecoder;
import cn.hutool.core.net.URLEncoder;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.enums.ResultUtil;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.ApiProperties;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.utils.CurrencyUtil;
import cn.lili.common.utils.SnowFlake;
import cn.lili.common.utils.StringUtils;
import cn.lili.common.vo.ResultMessage;
import cn.lili.modules.connect.entity.Connect;
import cn.lili.modules.connect.entity.enums.ConnectEnum;
import cn.lili.modules.connect.service.ConnectService;
import cn.lili.modules.member.entity.dto.ConnectQueryDTO;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.payment.entity.RefundLog;
import cn.lili.modules.payment.entity.enums.PaymentMethodEnum;
import cn.lili.modules.payment.kit.CashierSupport;
import cn.lili.modules.payment.kit.Payment;
import cn.lili.modules.payment.kit.core.PaymentHttpResponse;
import cn.lili.modules.payment.kit.core.enums.RequestMethodEnums;
import cn.lili.modules.payment.kit.core.enums.SignType;
import cn.lili.modules.payment.kit.core.kit.*;
import cn.lili.modules.payment.kit.core.utils.DateTimeZoneUtil;
import cn.lili.modules.payment.kit.dto.PayParam;
import cn.lili.modules.payment.kit.dto.PaymentSuccessParams;
import cn.lili.modules.payment.kit.params.dto.CashierParam;
import cn.lili.modules.payment.kit.plugin.wechat.enums.WechatApiEnum;
import cn.lili.modules.payment.kit.plugin.wechat.enums.WechatDomain;
import cn.lili.modules.payment.kit.plugin.wechat.model.*;
import cn.lili.modules.payment.service.PaymentService;
import cn.lili.modules.payment.service.RefundLogService;
import cn.lili.modules.system.entity.dos.Setting;
import cn.lili.modules.system.entity.dto.payment.WechatPaymentSetting;
import cn.lili.modules.system.entity.enums.SettingEnum;
import cn.lili.modules.system.service.SettingService;
import cn.lili.modules.wallet.entity.dos.MemberWithdrawApply;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ????????????
 *
 * @author Chopper
 * @since 2020/12/21 17:44
 */
@Slf4j
@Component
public class WechatPlugin implements Payment {

    /**
     * ?????????
     */
    @Autowired
    private CashierSupport cashierSupport;
    /**
     * ????????????
     */
    @Autowired
    private PaymentService paymentService;
    /**
     * ??????
     */
    @Autowired
    private Cache<String> cache;
    /**
     * ????????????
     */
    @Autowired
    private RefundLogService refundLogService;
    /**
     * API??????
     */
    @Autowired
    private ApiProperties apiProperties;
    /**
     * ??????
     */
    @Autowired
    private SettingService settingService;
    /**
     * ????????????
     */
    @Autowired
    private ConnectService connectService;
    /**
     * ????????????
     */
    @Autowired
    private OrderService orderService;


    @Override
    public ResultMessage<Object> h5pay(HttpServletRequest request, HttpServletResponse response1, PayParam payParam) {

        try {
            CashierParam cashierParam = cashierSupport.cashierParam(payParam);

            //??????????????????
            SceneInfo sceneInfo = new SceneInfo();
            sceneInfo.setPayer_client_ip(IpKit.getRealIp(request));
            H5Info h5Info = new H5Info();
            h5Info.setType("WAP");
            sceneInfo.setH5_info(h5Info);

            //????????????
            Integer fen = CurrencyUtil.fen(cashierParam.getPrice());
            //?????????????????????
            String outOrderNo = SnowFlake.getIdStr();
            //????????????
            String timeExpire = DateTimeZoneUtil.dateToTimeZone(System.currentTimeMillis() + 1000 * 60 * 3);

            //????????????
            String attach = URLEncoder.createDefault().encode(JSONUtil.toJsonStr(payParam), StandardCharsets.UTF_8);


            WechatPaymentSetting setting = wechatPaymentSetting();
            String appid = setting.getServiceAppId();
            if (appid == null) {
                throw new ServiceException(ResultCode.WECHAT_PAYMENT_NOT_SETTING);
            }
            UnifiedOrderModel unifiedOrderModel = new UnifiedOrderModel()
                    .setAppid(appid)
                    .setMchid(setting.getMchId())
                    .setDescription(cashierParam.getDetail())
                    .setOut_trade_no(outOrderNo)
                    .setTime_expire(timeExpire)
                    .setAttach(attach)
                    .setNotify_url(notifyUrl(apiProperties.getBuyer(), PaymentMethodEnum.WECHAT))
                    .setAmount(new Amount().setTotal(fen)).setScene_info(sceneInfo);

            log.info("?????????????????? {}", JSONUtil.toJsonStr(unifiedOrderModel));
            PaymentHttpResponse response = WechatApi.v3(
                    RequestMethodEnums.POST,
                    WechatDomain.CHINA.toString(),
                    WechatApiEnum.H5_PAY.toString(),
                    setting.getMchId(),
                    setting.getSerialNumber(),
                    null,
                    setting.getApiclient_key(),
                    JSONUtil.toJsonStr(unifiedOrderModel)
            );

            return ResultUtil.data(JSONUtil.toJsonStr(response.getBody()));
        } catch (Exception e) {
            log.error("??????H5????????????", e);
            throw new ServiceException(ResultCode.PAY_ERROR);
        }
    }

    @Override
    public ResultMessage<Object> jsApiPay(HttpServletRequest request, PayParam payParam) {

        try {
            Connect connect = connectService.queryConnect(
                    ConnectQueryDTO.builder().userId(UserContext.getCurrentUser().getId()).unionType(ConnectEnum.WECHAT.name()).build()
            );
            if (connect == null) {
                return null;
            }

            Payer payer = new Payer();
            payer.setOpenid(connect.getUnionId());

            CashierParam cashierParam = cashierSupport.cashierParam(payParam);

            //????????????
            Integer fen = CurrencyUtil.fen(cashierParam.getPrice());
            //?????????????????????
            String outOrderNo = SnowFlake.getIdStr();
            //????????????
            String timeExpire = DateTimeZoneUtil.dateToTimeZone(System.currentTimeMillis() + 1000 * 60 * 3);

            String attach = URLEncoder.createDefault().encode(JSONUtil.toJsonStr(payParam), StandardCharsets.UTF_8);

            WechatPaymentSetting setting = wechatPaymentSetting();
            String appid = setting.getServiceAppId();
            if (appid == null) {
                throw new ServiceException(ResultCode.WECHAT_PAYMENT_NOT_SETTING);
            }
            UnifiedOrderModel unifiedOrderModel = new UnifiedOrderModel()
                    .setAppid(appid)
                    .setMchid(setting.getMchId())
                    .setDescription(cashierParam.getDetail())
                    .setOut_trade_no(outOrderNo)
                    .setTime_expire(timeExpire)
                    .setAttach(attach)
                    .setNotify_url(notifyUrl(apiProperties.getBuyer(), PaymentMethodEnum.WECHAT))
                    .setAmount(new Amount().setTotal(fen))
                    .setPayer(payer);

            log.info("?????????????????? {}", JSONUtil.toJsonStr(unifiedOrderModel));
            PaymentHttpResponse response = WechatApi.v3(
                    RequestMethodEnums.POST,
                    WechatDomain.CHINA.toString(),
                    WechatApiEnum.JS_API_PAY.toString(),
                    setting.getMchId(),
                    setting.getSerialNumber(),
                    null,
                    setting.getApiclient_key(),
                    JSONUtil.toJsonStr(unifiedOrderModel)
            );
            //???????????????????????????????????????????????????????????????
            boolean verifySignature = WxPayKit.verifySignature(response, getPlatformCert());
            log.info("verifySignature: {}", verifySignature);
            log.info("?????????????????? {}", response);

            if (verifySignature) {
                String body = response.getBody();
                JSONObject jsonObject = JSONUtil.parseObj(body);
                String prepayId = jsonObject.getStr("prepay_id");
                Map<String, String> map = WxPayKit.jsApiCreateSign(appid, prepayId, setting.getApiclient_key());
                log.info("??????????????????:{}", map);

                return ResultUtil.data(map);
            }
            log.error("????????????????????????????????????????????????");
            throw new ServiceException(ResultCode.PAY_ERROR);
        } catch (Exception e) {
            log.error("????????????", e);
            throw new ServiceException(ResultCode.PAY_ERROR);
        }
    }

    @Override
    public ResultMessage<Object> appPay(HttpServletRequest request, PayParam payParam) {

        try {

            CashierParam cashierParam = cashierSupport.cashierParam(payParam);

            //????????????
            Integer fen = CurrencyUtil.fen(cashierParam.getPrice());
            //?????????????????????
            String outOrderNo = SnowFlake.getIdStr();
            //????????????
            String timeExpire = DateTimeZoneUtil.dateToTimeZone(System.currentTimeMillis() + 1000 * 60 * 3);

            String attach = URLEncoder.createDefault().encode(JSONUtil.toJsonStr(payParam), StandardCharsets.UTF_8);

            WechatPaymentSetting setting = wechatPaymentSetting();
            String appid = setting.getAppId();
            if (appid == null) {
                throw new ServiceException(ResultCode.WECHAT_PAYMENT_NOT_SETTING);
            }
            UnifiedOrderModel unifiedOrderModel = new UnifiedOrderModel()
                    .setAppid(appid)
                    .setMchid(setting.getMchId())
                    .setDescription(cashierParam.getDetail())
                    .setOut_trade_no(outOrderNo)
                    .setTime_expire(timeExpire)
                    .setAttach(attach)
                    .setNotify_url(notifyUrl(apiProperties.getBuyer(), PaymentMethodEnum.WECHAT))
                    .setAmount(new Amount().setTotal(fen));


            log.info("?????????????????? {}", JSONUtil.toJsonStr(unifiedOrderModel));
            PaymentHttpResponse response = WechatApi.v3(
                    RequestMethodEnums.POST,
                    WechatDomain.CHINA.toString(),
                    WechatApiEnum.APP_PAY.toString(),
                    setting.getMchId(),
                    setting.getSerialNumber(),
                    null,
                    setting.getApiclient_key(),
                    JSONUtil.toJsonStr(unifiedOrderModel)
            );
            //???????????????????????????????????????????????????????????????
            boolean verifySignature = WxPayKit.verifySignature(response, getPlatformCert());
            log.info("verifySignature: {}", verifySignature);
            log.info("?????????????????? {}", response);

            if (verifySignature) {
                JSONObject jsonObject = JSONUtil.parseObj(response.getBody());
                String prepayId = jsonObject.getStr("prepay_id");
                Map<String, String> map = WxPayKit.appPrepayIdCreateSign(appid,
                        setting.getMchId(),
                        prepayId,
                        setting.getApiclient_key(), SignType.MD5);
                log.info("??????????????????:{}", map);

                return ResultUtil.data(map);
            }
            log.error("????????????????????????????????????????????????");
            throw new ServiceException(ResultCode.PAY_ERROR);
        } catch (Exception e) {
            log.error("????????????", e);
            throw new ServiceException(ResultCode.PAY_ERROR);
        }
    }

    @Override
    public ResultMessage<Object> nativePay(HttpServletRequest request, PayParam payParam) {

        try {

            CashierParam cashierParam = cashierSupport.cashierParam(payParam);

            //????????????
            Integer fen = CurrencyUtil.fen(cashierParam.getPrice());
            //?????????????????????
            String outOrderNo = SnowFlake.getIdStr();
            //????????????
            String timeExpire = DateTimeZoneUtil.dateToTimeZone(System.currentTimeMillis() + 1000 * 60 * 3);

            String attach = URLEncoder.createDefault().encode(JSONUtil.toJsonStr(payParam), StandardCharsets.UTF_8);

            WechatPaymentSetting setting = wechatPaymentSetting();

            String appid = setting.getServiceAppId();
            if (appid == null) {
                throw new ServiceException(ResultCode.WECHAT_PAYMENT_NOT_SETTING);
            }
            UnifiedOrderModel unifiedOrderModel = new UnifiedOrderModel()
                    .setAppid(appid)
                    .setMchid(setting.getMchId())
                    .setDescription(cashierParam.getDetail())
                    .setOut_trade_no(outOrderNo)
                    .setTime_expire(timeExpire)
                    //????????????
                    .setAttach(attach)
                    .setNotify_url(notifyUrl(apiProperties.getBuyer(), PaymentMethodEnum.WECHAT))
                    .setAmount(new Amount().setTotal(fen));

            log.info("?????????????????? {}", JSONUtil.toJsonStr(unifiedOrderModel));
            PaymentHttpResponse response = WechatApi.v3(
                    RequestMethodEnums.POST,
                    WechatDomain.CHINA.toString(),
                    WechatApiEnum.NATIVE_PAY.toString(),
                    setting.getMchId(),
                    setting.getSerialNumber(),
                    null,
                    setting.getApiclient_key(),
                    JSONUtil.toJsonStr(unifiedOrderModel)
            );
            log.info("?????????????????? {}", response);
            //???????????????????????????????????????????????????????????????
            boolean verifySignature = WxPayKit.verifySignature(response, getPlatformCert());
            log.info("verifySignature: {}", verifySignature);

            if (verifySignature) {
                return ResultUtil.data(new JSONObject(response.getBody()).getStr("code_url"));
            } else {
                log.error("????????????????????????????????????????????????");
                throw new ServiceException(ResultCode.PAY_ERROR);
            }
        } catch (ServiceException e) {
            log.error("????????????", e);
            throw new ServiceException(ResultCode.PAY_ERROR);
        } catch (Exception e) {
            log.error("????????????", e);
            throw new ServiceException(ResultCode.PAY_ERROR);
        }
    }

    @Override
    public ResultMessage<Object> mpPay(HttpServletRequest request, PayParam payParam) {

        try {
            Connect connect = connectService.queryConnect(
                    ConnectQueryDTO.builder().userId(UserContext.getCurrentUser().getId()).unionType(ConnectEnum.WECHAT_MP_OPEN_ID.name()).build()
            );
            if (connect == null) {
                return null;
            }

            Payer payer = new Payer();
            payer.setOpenid(connect.getUnionId());

            CashierParam cashierParam = cashierSupport.cashierParam(payParam);

            //????????????
            Integer fen = CurrencyUtil.fen(cashierParam.getPrice());
            //?????????????????????
            String outOrderNo = SnowFlake.getIdStr();
            //????????????
            String timeExpire = DateTimeZoneUtil.dateToTimeZone(System.currentTimeMillis() + 1000 * 60 * 3);

            //??????????????????appid ?????????????????????????????????????????????????????????appid ???????????????????????????????????????????????????appid????????????????????????
            //?????????2?????????????????????????????????????????????appid?????????????????????
            String appid = wechatPaymentSetting().getMpAppId();
            if (StringUtils.isEmpty(appid)) {
                throw new ServiceException(ResultCode.WECHAT_PAYMENT_NOT_SETTING);
            }
            String attach = URLEncoder.createDefault().encode(JSONUtil.toJsonStr(payParam), StandardCharsets.UTF_8);

            WechatPaymentSetting setting = wechatPaymentSetting();
            UnifiedOrderModel unifiedOrderModel = new UnifiedOrderModel()
                    .setAppid(appid)
                    .setMchid(setting.getMchId())
                    .setDescription(cashierParam.getDetail())
                    .setOut_trade_no(outOrderNo)
                    .setTime_expire(timeExpire)
                    .setAttach(attach)
                    .setNotify_url(notifyUrl(apiProperties.getBuyer(), PaymentMethodEnum.WECHAT))
                    .setAmount(new Amount().setTotal(fen))
                    .setPayer(payer);

            log.info("?????????????????? {}", JSONUtil.toJsonStr(unifiedOrderModel));
            PaymentHttpResponse response = WechatApi.v3(
                    RequestMethodEnums.POST,
                    WechatDomain.CHINA.toString(),
                    WechatApiEnum.JS_API_PAY.toString(),
                    setting.getMchId(),
                    setting.getSerialNumber(),
                    null,
                    setting.getApiclient_key(),
                    JSONUtil.toJsonStr(unifiedOrderModel)
            );
            //???????????????????????????????????????????????????????????????
            boolean verifySignature = WxPayKit.verifySignature(response, getPlatformCert());
            log.info("verifySignature: {}", verifySignature);
            log.info("?????????????????? {}", response);

            if (verifySignature) {
                String body = response.getBody();
                JSONObject jsonObject = JSONUtil.parseObj(body);
                String prepayId = jsonObject.getStr("prepay_id");
                Map<String, String> map = WxPayKit.jsApiCreateSign(appid, prepayId, setting.getApiclient_key());
                log.info("??????????????????:{}", map);

                return ResultUtil.data(map);
            }
            log.error("????????????????????????????????????????????????");
            throw new ServiceException(ResultCode.PAY_ERROR);
        } catch (Exception e) {
            log.error("????????????", e);
            throw new ServiceException(ResultCode.PAY_ERROR);
        }

    }

    @Override
    public void callBack(HttpServletRequest request) {
        try {
            verifyNotify(request);
        } catch (Exception e) {
            log.error("????????????", e);
        }
    }

    @Override
    public void notify(HttpServletRequest request) {
        try {
            verifyNotify(request);
        } catch (Exception e) {
            log.error("????????????", e);
        }
    }

    /**
     * ????????????
     * ???????????????https://pay.weixin.qq.com/docs/merchant/apis/batch-transfer-to-balance/transfer-batch/initiate-batch-transfer.html
     *
     * @param memberWithdrawApply ??????????????????
     */
    @Override
    public void transfer(MemberWithdrawApply memberWithdrawApply) {

        try {
            WechatPaymentSetting setting = wechatPaymentSetting();
            Connect connect = connectService.queryConnect(
                    ConnectQueryDTO.builder().userId(UserContext.getCurrentUser().getId())
                            .unionType(ConnectEnum.WECHAT_OPEN_ID.name()).build()
            );
            //????????????????????????AppId,?????????????????????????????????APPID????????????openID?????????????????????APPID??????
            TransferModel transferModel = new TransferModel()
                    .setAppid(setting.getServiceAppId())
                    .setOut_batch_no(SnowFlake.createStr("T"))
                    .setBatch_name("????????????")
                    .setBatch_remark("????????????")
                    .setTotal_amount(CurrencyUtil.fen(memberWithdrawApply.getApplyMoney()))
                    .setTotal_num(1)
                    .setTransfer_scene_id("1000");
            List<TransferDetailInput> transferDetailListList = new ArrayList<>();
            {
                TransferDetailInput transferDetailInput = new TransferDetailInput();
                transferDetailInput.setOut_detail_no(SnowFlake.createStr("TD"));
                transferDetailInput.setTransfer_amount(CurrencyUtil.fen(memberWithdrawApply.getApplyMoney()));
                transferDetailInput.setTransfer_remark("????????????");
                transferDetailInput.setOpenid(connect.getUnionId());
//                transferDetailInput.setUserName(
//                        "757b340b45ebef5467rter35gf464344v3542sdf4t6re4tb4f54ty45t4yyry45");
//                transferDetailInput.setUserIdCard(
//                        "8609cb22e1774a50a930e414cc71eca06121bcd266335cda230d24a7886a8d9f");
                transferDetailListList.add(transferDetailInput);
            }
            transferModel.setTransfer_detail_list(transferDetailListList);

            PaymentHttpResponse response = WechatApi.v3(
                    RequestMethodEnums.POST,
                    WechatDomain.CHINA.toString(),
                    WechatApiEnum.TRANSFER_BATCHES.toString(),
                    setting.getMchId(),
                    setting.getSerialNumber(),
                    null,
                    setting.getApiclient_key(),
                    JSONUtil.toJsonStr(transferModel)
            );
            log.info("?????????????????? {}", response);
            //????????????????????????????????????????????????
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * ?????????????????????????????????
     *
     * @param request
     * @throws Exception
     */
    private void verifyNotify(HttpServletRequest request) throws Exception {

        String timestamp = request.getHeader("Wechatpay-Timestamp");
        String nonce = request.getHeader("Wechatpay-Nonce");
        String serialNo = request.getHeader("Wechatpay-Serial");
        String signature = request.getHeader("Wechatpay-Signature");

        log.info("timestamp:{} nonce:{} serialNo:{} signature:{}", timestamp, nonce, serialNo, signature);
        String result = HttpKit.readData(request);
        log.info("???????????????????????? {}", result);

        WechatPaymentSetting setting = wechatPaymentSetting();
        //??????????????????????????
        String plainText = WxPayKit.verifyNotify(serialNo, result, signature, nonce, timestamp,
                setting.getApiKey3(), Objects.requireNonNull(getPlatformCert()));

        log.info("???????????????????????? {}", plainText);

        JSONObject jsonObject = JSONUtil.parseObj(plainText);

        String payParamStr = jsonObject.getStr("attach");
        String payParamJson = URLDecoder.decode(payParamStr, StandardCharsets.UTF_8);
        PayParam payParam = JSONUtil.toBean(payParamJson, PayParam.class);


        String tradeNo = jsonObject.getStr("transaction_id");
        Double totalAmount = CurrencyUtil.reversalFen(jsonObject.getJSONObject("amount").getDouble("total"));

        PaymentSuccessParams paymentSuccessParams = new PaymentSuccessParams(
                PaymentMethodEnum.WECHAT.name(),
                tradeNo,
                totalAmount,
                payParam
        );

        paymentService.success(paymentSuccessParams);
        log.info("?????????????????????????????????{}", plainText);
    }

    @Override
    public void refund(RefundLog refundLog) {

        try {

            Amount amount = new Amount().setRefund(CurrencyUtil.fen(refundLog.getTotalAmount()))
                    .setTotal(CurrencyUtil.fen(orderService.getPaymentTotal(refundLog.getOrderSn())));

            //??????????????????
            RefundModel refundModel = new RefundModel()
                    .setTransaction_id(refundLog.getPaymentReceivableNo())
                    .setOut_refund_no(refundLog.getOutOrderNo())
                    .setReason(refundLog.getRefundReason())
                    .setAmount(amount)
                    .setNotify_url(refundNotifyUrl(apiProperties.getBuyer(), PaymentMethodEnum.WECHAT));

            WechatPaymentSetting setting = wechatPaymentSetting();

            log.info("?????????????????? {}", JSONUtil.toJsonStr(refundModel));
            PaymentHttpResponse response = WechatApi.v3(
                    RequestMethodEnums.POST,
                    WechatDomain.CHINA.toString(),
                    WechatApiEnum.DOMESTIC_REFUNDS.toString(),
                    setting.getMchId(),
                    setting.getSerialNumber(),
                    null,
                    setting.getApiclient_key(),
                    JSONUtil.toJsonStr(refundModel)
            );
            log.info("?????????????????? {}", response);
            //??????????????????
            if (response.getStatus() == 200) {
                refundLogService.save(refundLog);
            } else {
                //??????????????????
                refundLog.setErrorMessage(response.getBody());
                refundLogService.save(refundLog);
            }
        } catch (Exception e) {
            log.error("????????????????????????", e);
        }

    }

    @Override
    public void refundNotify(HttpServletRequest request) {
        String timestamp = request.getHeader("Wechatpay-Timestamp");
        String nonce = request.getHeader("Wechatpay-Nonce");
        String serialNo = request.getHeader("Wechatpay-Serial");
        String signature = request.getHeader("Wechatpay-Signature");

        log.info("timestamp:{} nonce:{} serialNo:{} signature:{}", timestamp, nonce, serialNo, signature);
        String result = HttpKit.readData(request);
        log.info("???????????????????????? {}", result);
        JSONObject ciphertext = JSONUtil.parseObj(result);

        try { //??????????????????????????
            String plainText = WxPayKit.verifyNotify(serialNo, result, signature, nonce, timestamp,
                    wechatPaymentSetting().getApiKey3(), Objects.requireNonNull(getPlatformCert()));
            log.info("???????????????????????? {}", plainText);

            if (("REFUND.SUCCESS").equals(ciphertext.getStr("event_type"))) {
                log.info("???????????? {}", plainText);
                //????????????????????????
                JSONObject jsonObject = JSONUtil.parseObj(plainText);
                String transactionId = jsonObject.getStr("transaction_id");
                String refundId = jsonObject.getStr("refund_id");

                RefundLog refundLog = refundLogService.getOne(new LambdaQueryWrapper<RefundLog>().eq(RefundLog::getPaymentReceivableNo, transactionId));
                if (refundLog != null) {
                    refundLog.setIsRefund(true);
                    refundLog.setReceivableNo(refundId);
                    refundLogService.saveOrUpdate(refundLog);
                }

            } else {
                log.info("???????????? {}", plainText);
                JSONObject jsonObject = JSONUtil.parseObj(plainText);
                String transactionId = jsonObject.getStr("transaction_id");
                String refundId = jsonObject.getStr("refund_id");

                RefundLog refundLog = refundLogService.getOne(new LambdaQueryWrapper<RefundLog>().eq(RefundLog::getPaymentReceivableNo, transactionId));
                if (refundLog != null) {
                    refundLog.setReceivableNo(refundId);
                    refundLog.setErrorMessage(ciphertext.getStr("summary"));
                    refundLogService.saveOrUpdate(refundLog);
                }
            }
        } catch (Exception e) {
            log.error("??????????????????", e);
        }
    }

    /**
     * ????????????????????????
     *
     * @return
     */
    private WechatPaymentSetting wechatPaymentSetting() {
        try {
            Setting systemSetting = settingService.get(SettingEnum.WECHAT_PAYMENT.name());
            WechatPaymentSetting wechatPaymentSetting = JSONUtil.toBean(systemSetting.getSettingValue(), WechatPaymentSetting.class);
            return wechatPaymentSetting;
        } catch (Exception e) {
            log.error("????????????????????????", e);
            throw new ServiceException(ResultCode.PAY_NOT_SUPPORT);
        }
    }

    /**
     * ??????????????????
     *
     * @return ????????????
     */
    private X509Certificate getPlatformCert() {
        //?????????????????????????????????????????????????????????????????????????????????
        String publicCert = cache.getString(CachePrefix.WECHAT_PLAT_FORM_CERT.getPrefix());
        if (!StringUtils.isEmpty(publicCert)) {
            return PayKit.getCertificate(publicCert);
        }
        //????????????????????????
        try {

            WechatPaymentSetting setting = wechatPaymentSetting();

            PaymentHttpResponse response = WechatApi.v3(
                    RequestMethodEnums.GET,
                    WechatDomain.CHINA.toString(),
                    WechatApiEnum.GET_CERTIFICATES.toString(),
                    setting.getMchId(),
                    setting.getSerialNumber(),
                    null,
                    setting.getApiclient_key(),
                    ""
            );
            String body = response.getBody();
            log.info("????????????????????????body: {}", body);
            if (response.getStatus() == 200) {
                JSONObject jsonObject = JSONUtil.parseObj(body);
                JSONArray dataArray = jsonObject.getJSONArray("data");
                //????????????????????????????????????
                JSONObject encryptObject = dataArray.getJSONObject(0);
                JSONObject encryptCertificate = encryptObject.getJSONObject("encrypt_certificate");
                String associatedData = encryptCertificate.getStr("associated_data");
                String cipherText = encryptCertificate.getStr("ciphertext");
                String nonce = encryptCertificate.getStr("nonce");
                publicCert = getPlatformCertStr(associatedData, nonce, cipherText);
                long second = (PayKit.getCertificate(publicCert).getNotAfter().getTime() - System.currentTimeMillis()) / 1000;
                cache.put(CachePrefix.WECHAT_PLAT_FORM_CERT.getPrefix(), publicCert, second);
            } else {
                log.error("?????????????????????{}" + body);
                throw new ServiceException(ResultCode.WECHAT_CERT_ERROR);
            }
            return PayKit.getCertificate(publicCert);
        } catch (Exception e) {
            log.error("??????????????????", e);
        }
        return null;
    }

    /**
     * ????????????????????????????????????
     * ????????????????????????
     *
     * @param associatedData ????????????
     * @param nonce          ????????????
     * @param cipherText     ????????????
     * @return platform key
     * @throws GeneralSecurityException ??????????????????
     */
    private String getPlatformCertStr(String associatedData, String nonce, String cipherText) throws GeneralSecurityException {


        AesUtil aesUtil = new AesUtil(wechatPaymentSetting().getApiKey3().getBytes(StandardCharsets.UTF_8));
        //????????????????????????
        //encrypt_certificate ??????  associated_data nonce  ciphertext
        return aesUtil.decryptToString(
                associatedData.getBytes(StandardCharsets.UTF_8),
                nonce.getBytes(StandardCharsets.UTF_8),
                cipherText
        );
    }
}
