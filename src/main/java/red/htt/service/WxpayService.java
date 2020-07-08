package red.htt.service;

import com.github.wxpay.sdk.*;
import org.apache.commons.io.IOUtils;
import red.htt.bean.*;
import red.htt.bean.wx.MinappPayRes;
import red.htt.bean.wx.NativePayRes;

import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * @author mio
 * @see " https://pay.weixin.qq.com/wiki/doc/api/native.php?chapter=9_1"
 */
public class WxpayService {

    private final WXPayConfig config;

    public WxpayService(WXPayConfig config) {
        InnerWxpayConfigImpl con = new InnerWxpayConfigImpl()
                .setAppId(config.getAppID())
                .setMchId(config.getMchID())
                .setNotifyUrl(config.getNotifyUrl())
                .setUseSandbox(config.useSandbox())
                .setCertStream(config.getCertStream())
                .setHttpConnectTimeoutMs(config.getHttpConnectTimeoutMs())
                .setHttpReadTimeoutMs(config.getHttpReadTimeoutMs())
                .setwXPayDomain(config.getWXPayDomain())
                .setShouldAutoReport(config.shouldAutoReport())
                .setReportWorkerNum(config.getReportWorkerNum())
                .setReportQueueMaxSize(config.getReportQueueMaxSize())
                .setReportBatchSize(config.getReportBatchSize());
        if (config.useSandbox()) {
            con.setKey(this.getSandboxKey(config));
        }
        this.config = con;
    }

    /**
     * NATIVE 支付<br>
     * 请求微信统一下单接口
     *
     * @param order order
     * @return NativePayRes
     * @see "https://pay.weixin.qq.com/wiki/doc/api/native.php?chapter=6_1"
     */
    public NativePayRes nativePay(Order order) {
        NativePayRes res = new NativePayRes();
        res.setSuccess(false);
        try {
            WXPay wxpay = new WXPay(config, config.getNotifyUrl(), true, config.useSandbox());
            Map<String, String> data = this.genUnifiedOrderData(order, "NATIVE");
            Map<String, String> rm = wxpay.unifiedOrder(data);
            String returnCode = rm.getOrDefault("return_code", "").toUpperCase();
            String returnMsg = rm.getOrDefault("return_msg", "");
            String resultCode = rm.getOrDefault("result_code", "").toUpperCase();
            String errCode = rm.getOrDefault("err_code", "");
            String errCodeDes = rm.getOrDefault("err_code_des", "");
            String codeUrl = rm.getOrDefault("code_url", "");
            if (!"SUCCESS".equals(returnCode)) {
                return res.setErrorMsg(returnMsg);
            }
            if (!"SUCCESS".equals(resultCode)) {
                return res.setErrorCode(errCode).setErrorMsg(errCodeDes);
            }
            return res.setSuccess(true).setQrCode(codeUrl);
        } catch (Exception e) {
            e.printStackTrace();
            return res.setSuccess(false).setErrorMsg(e.getLocalizedMessage());
        }
    }

    /**
     * 小程序支付
     * 请求微信统一下单接口
     *
     * @param order  order
     * @param openId openId
     * @return Result
     * @see "https://pay.weixin.qq.com/wiki/doc/api/wxa/wxa_api.php?chapter=7_3&index=1"
     */
    public Result<MinappPayRes> minappPay(Order order, String openId) {
        try {
            WXPay wxpay = new WXPay(config);
            Map<String, String> data = this.genUnifiedOrderData(order, "JSAPI");
            data.put("openid", openId);
            Map<String, String> resp = wxpay.unifiedOrder(data);

            MinappPayRes respVO = new MinappPayRes();
            String timeStamp = System.currentTimeMillis() / 1000 + "";
            respVO.setTimeStamp(timeStamp);
            respVO.setNonceStr(resp.get("nonce_str"));
            respVO.setPrepayId(resp.get("prepay_id"));
            respVO.setSignType(WXPayConstants.SignType.MD5.toString());

            // 再生成小程序支付用的签名
            Map<String, String> signData = new HashMap<>(5);
            signData.put("appId", config.getAppID());
            signData.put("timeStamp", timeStamp);
            signData.put("nonceStr", respVO.getNonceStr());
            signData.put("package", "prepay_id=" + respVO.getPrepayId());
            signData.put("signType", respVO.getSignType());
            respVO.setPaySign(WXPayUtil.generateSignature(signData, config.getKey()));
            return Result.success(respVO);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error();
        }
    }

    /**
     * <p>微信支付结果通知</p>
     * <p>本方法会验证 签名、商户ID、appId, 验证通过后返回订单ID, 调用方可对订单做进一步验证</p>
     * <p>支付完成后，商户需要接收处理，并按文档规范返回应答</p>
     *
     * @param request request
     * @return NotifyRes
     * @see "https://pay.weixin.qq.com/wiki/doc/api/wxa/wxa_api.php?chapter=9_7&index=8pay"
     */
    public NotifyRes notify(HttpServletRequest request) {
        NotifyRes res = new NotifyRes();
        res.setSuccess(false);
        try {
            // 支付结果通知的xml格式数据
            String notifyData = IOUtils.toString(request.getInputStream());
            WXPay wxpay = new WXPay(config);

            // 转换成map
            Map<String, String> rm = WXPayUtil.xmlToMap(notifyData);
            boolean valid = wxpay.isPayResultNotifySignatureValid(rm);
            if (!valid) {
                return res.setErrorMsg("签名错误");
            }

            //商户订单号
            String orderId = rm.getOrDefault("out_trade_no", "");

            // 商户ID
            String mchId = rm.getOrDefault("mch_id", "");

            // appId
            String appId = rm.getOrDefault("appid", "");

            if (!config.getMchID().equals(mchId)) {
                return res.setErrorMsg("商户ID不正确");
            }

            if (!config.getAppID().equals(appId)) {
                return res.setErrorMsg("appId不正确");
            }

            // 按微信文档, 返回成功消息
            Map<String, String> retMap = new HashMap<>();
            retMap.put("return_code", "SUCCESS");
            retMap.put("return_msg", "OK");
            res.setReturnRes(WXPayUtil.mapToXml(retMap));
            return res.setSuccess(true).setOrderId(orderId);
        } catch (Exception e) {
            e.printStackTrace();
            return res;
        }
    }


    /**
     * 生成统一下单接口的数据
     *
     * @param tradeType 交易类型, JSAPI--JSAPI支付（或小程序支付）、NATIVE--Native支付、APP--app支付，MWEB--H5支付
     * @see "https://pay.weixin.qq.com/wiki/doc/api/wxa/wxa_api.php?chapter=9_1#"
     */
    private Map<String, String> genUnifiedOrderData(Order order, String tradeType) {
        Map<String, String> data = new HashMap<>();
        data.put("body", order.getBody());
        data.put("out_trade_no", order.getId());
        data.put("device_info", "");
        data.put("fee_type", order.getFeeType());
        data.put("total_fee", order.getPrice());
        data.put("spbill_create_ip", "123.12.12.123");
        data.put("notify_url", config.getNotifyUrl());
        data.put("trade_type", tradeType);
        data.put("product_id", order.getId());
        return data;
    }

    private String getSandboxKey(WXPayConfig config) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("mch_id", config.getMchID());
            params.put("nonce_str", WXPayUtil.generateNonceStr());
            params.put("sign", WXPayUtil.generateSignature(params, config.getKey()));
            String respString = new WXPay(config, true).requestWithoutCert("/sandboxnew/pay/getsignkey", params, 8000, 10000);
            Map<String, String> result = WXPayUtil.xmlToMap(respString);
            if ("SUCCESS".equals(result.get("return_code"))) {
                return result.get("sandbox_signkey");
            } else {
                throw new RuntimeException();
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private static class InnerWxpayConfigImpl extends WXPayConfig {

        private String appId;

        private String mchId;

        private String key;

        private String notifyUrl;

        private boolean useSandbox;

        private InputStream certStream;

        private int httpConnectTimeoutMs;

        private int httpReadTimeoutMs;

        private IWXPayDomain wXPayDomain;

        private boolean shouldAutoReport;

        private int reportWorkerNum;

        private int reportQueueMaxSize;


        private int reportBatchSize;

        @Override
        public String getAppID() {
            return appId;
        }

        public InnerWxpayConfigImpl setAppId(String appId) {
            this.appId = appId;
            return this;
        }


        public InnerWxpayConfigImpl setMchId(String mchId) {
            this.mchId = mchId;
            return this;
        }

        @Override
        public String getMchID() {
            return mchId;
        }


        @Override
        public String getKey() {
            return this.key;
        }

        public InnerWxpayConfigImpl setKey(String key) {
            this.key = key;
            return this;
        }

        @Override
        public String getNotifyUrl() {
            return notifyUrl;
        }

        public InnerWxpayConfigImpl setNotifyUrl(String notifyUrl) {
            this.notifyUrl = notifyUrl;
            return this;
        }

        @Override
        public boolean useSandbox() {
            return this.useSandbox;
        }

        public InnerWxpayConfigImpl setUseSandbox(boolean useSandbox) {
            this.useSandbox = useSandbox;
            return this;
        }

        @Override
        public InputStream getCertStream() {
            return this.certStream;
        }

        public InnerWxpayConfigImpl setCertStream(InputStream certStream) {
            this.certStream = certStream;
            return this;
        }


        @Override
        public int getHttpConnectTimeoutMs() {
            return this.httpConnectTimeoutMs;
        }

        public InnerWxpayConfigImpl setHttpConnectTimeoutMs(int httpConnectTimeoutMs) {
            this.httpConnectTimeoutMs = httpConnectTimeoutMs;
            return this;
        }


        @Override
        public int getHttpReadTimeoutMs() {
            return this.httpReadTimeoutMs;
        }

        public InnerWxpayConfigImpl setHttpReadTimeoutMs(int httpReadTimeoutMs) {
            this.httpReadTimeoutMs = httpReadTimeoutMs;
            return this;
        }

        @Override
        public IWXPayDomain getWXPayDomain() {
            return this.wXPayDomain;
        }


        public InnerWxpayConfigImpl setwXPayDomain(IWXPayDomain wXPayDomain) {
            this.wXPayDomain = wXPayDomain;
            return this;
        }


        @Override
        public boolean shouldAutoReport() {
            return this.shouldAutoReport;
        }


        public InnerWxpayConfigImpl setShouldAutoReport(boolean shouldAutoReport) {
            this.shouldAutoReport = shouldAutoReport;
            return this;
        }


        @Override
        public int getReportWorkerNum() {
            return reportWorkerNum;
        }

        public InnerWxpayConfigImpl setReportWorkerNum(int reportWorkerNum) {
            this.reportWorkerNum = reportWorkerNum;
            return this;
        }

        @Override
        public int getReportQueueMaxSize() {
            return reportQueueMaxSize;
        }

        public InnerWxpayConfigImpl setReportQueueMaxSize(int reportQueueMaxSize) {
            this.reportQueueMaxSize = reportQueueMaxSize;
            return this;
        }

        @Override
        public int getReportBatchSize() {
            return reportBatchSize;
        }

        public InnerWxpayConfigImpl setReportBatchSize(int reportBatchSize) {
            this.reportBatchSize = reportBatchSize;
            return this;
        }
    }
}
