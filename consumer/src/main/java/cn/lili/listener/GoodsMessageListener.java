package cn.lili.listener;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ClassLoaderUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.lili.common.aop.annotation.RetryOperation;
import cn.lili.common.exception.RetryException;
import cn.lili.common.vo.PageVO;
import cn.lili.event.GoodsCommentCompleteEvent;
import cn.lili.modules.distribution.entity.dos.DistributionGoods;
import cn.lili.modules.distribution.entity.dto.DistributionGoodsSearchParams;
import cn.lili.modules.distribution.service.DistributionGoodsService;
import cn.lili.modules.distribution.service.DistributionSelectedGoodsService;
import cn.lili.modules.goods.entity.dos.*;
import cn.lili.modules.goods.entity.dto.GoodsCompleteMessage;
import cn.lili.modules.goods.entity.dto.GoodsParamsDTO;
import cn.lili.modules.goods.entity.dto.GoodsSearchParams;
import cn.lili.modules.goods.entity.enums.GoodsAuthEnum;
import cn.lili.modules.goods.entity.enums.GoodsStatusEnum;
import cn.lili.modules.goods.service.*;
import cn.lili.modules.member.entity.dos.FootPrint;
import cn.lili.modules.member.entity.dos.MemberEvaluation;
import cn.lili.modules.member.service.FootprintService;
import cn.lili.modules.member.service.GoodsCollectionService;
import cn.lili.modules.promotion.entity.dos.BasePromotions;
import cn.lili.modules.promotion.entity.dos.PromotionGoods;
import cn.lili.modules.promotion.entity.dto.search.PromotionGoodsSearchParams;
import cn.lili.modules.promotion.entity.enums.PromotionsScopeTypeEnum;
import cn.lili.modules.promotion.service.PromotionGoodsService;
import cn.lili.modules.promotion.service.PromotionService;
import cn.lili.modules.search.entity.dos.EsGoodsIndex;
import cn.lili.modules.search.service.EsGoodsIndexService;
import cn.lili.rocketmq.tags.GoodsTagsEnum;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ????????????
 *
 * @author paulG
 * @since 2020/12/9
 **/
@Component
@Slf4j
@RocketMQMessageListener(topic = "${lili.data.rocketmq.goods-topic}", consumerGroup = "${lili.data.rocketmq.goods-group}")
public class GoodsMessageListener implements RocketMQListener<MessageExt> {

    private static final int BATCH_SIZE = 10;

    /**
     * ES??????
     */
    @Autowired
    private EsGoodsIndexService goodsIndexService;
    /**
     * ??????
     */
    @Autowired
    private GoodsService goodsService;
    /**
     * ??????Sku
     */
    @Autowired
    private GoodsSkuService goodsSkuService;
    /**
     * ????????????
     */
    @Autowired
    private FootprintService footprintService;
    /**
     * ????????????
     */
    @Autowired
    private GoodsCollectionService goodsCollectionService;
    /**
     * ????????????
     */
    @Autowired
    private List<GoodsCommentCompleteEvent> goodsCommentCompleteEvents;
    /**
     * ????????????
     */
    @Autowired
    private DistributionGoodsService distributionGoodsService;
    /**
     * ?????????-???????????????
     */
    @Autowired
    private DistributionSelectedGoodsService distributionSelectedGoodsService;
    /**
     * ??????
     */
    @Autowired
    private CategoryService categoryService;
    /**
     * ??????
     */
    @Autowired
    private BrandService brandService;
    /**
     * ??????????????????
     */
    @Autowired
    private StoreGoodsLabelService storeGoodsLabelService;

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private PromotionGoodsService promotionGoodsService;

    @Override
    @RetryOperation
    public void onMessage(MessageExt messageExt) {

        switch (GoodsTagsEnum.valueOf(messageExt.getTags())) {
            //????????????
            case VIEW_GOODS:
                FootPrint footPrint = JSONUtil.toBean(new String(messageExt.getBody()), FootPrint.class);
                footprintService.saveFootprint(footPrint);
                break;
            //????????????
            case GENERATOR_GOODS_INDEX:
                try {
                    String goodsId = new String(messageExt.getBody());
                    log.info("????????????: {}", goodsId);
                    Goods goods = this.goodsService.getById(goodsId);
                    this.updateGoodsIndex(goods);
                } catch (Exception e) {
                    log.error("???????????????????????????????????????????????????: " + new String(messageExt.getBody()), e);
                }
                break;
            case GENERATOR_STORE_GOODS_INDEX:
                try {
                    String storeId = new String(messageExt.getBody());
                    this.updateGoodsIndex(storeId);
                } catch (Exception e) {
                    log.error("?????????????????????????????????????????????????????????: " + new String(messageExt.getBody()), e);
                }
                break;
            case UPDATE_GOODS_INDEX_PROMOTIONS:
                this.updateGoodsIndexPromotions(new String(messageExt.getBody()));
                break;
            case DELETE_GOODS_INDEX_PROMOTIONS:
                JSONObject jsonObject = JSONUtil.parseObj(new String(messageExt.getBody()));
                String promotionKey = jsonObject.getStr("promotionKey");
                if (CharSequenceUtil.isEmpty(promotionKey)) {
                    break;
                }
                if (CharSequenceUtil.isNotEmpty(jsonObject.getStr("scopeId"))) {
                    this.goodsIndexService.deleteEsGoodsPromotionByPromotionKey(Arrays.asList(jsonObject.getStr("scopeId").split(",")), promotionKey);
                } else {
                    this.goodsIndexService.deleteEsGoodsPromotionByPromotionKey(promotionKey);
                }
                break;
            case UPDATE_GOODS_INDEX:
                try {
                    String goodsIdsJsonStr = new String(messageExt.getBody());
                    GoodsSearchParams searchParams = new GoodsSearchParams();
                    searchParams.setId(ArrayUtil.join(JSONUtil.toList(goodsIdsJsonStr, String.class).toArray(), ","));
                    List<Goods> goodsList = goodsService.queryListByParams(searchParams);
                    this.updateGoodsIndex(goodsList);
                } catch (Exception e) {
                    log.error("???????????????????????????????????????????????????: " + new String(messageExt.getBody()), e);
                }
                break;
            case UPDATE_GOODS_INDEX_FIELD:
                try {
                    String updateIndexFieldsJsonStr = new String(messageExt.getBody());
                    JSONObject updateIndexFields = JSONUtil.parseObj(updateIndexFieldsJsonStr);
                    @SuppressWarnings("unchecked") Map<String, Object> queryFields = updateIndexFields.get("queryFields", Map.class);
                    @SuppressWarnings("unchecked") Map<String, Object> updateFields = updateIndexFields.get("updateFields", Map.class);
                    goodsIndexService.updateIndex(queryFields, updateFields);
                } catch (Exception e) {
                    log.error("???????????????????????????????????????????????????: " + new String(messageExt.getBody()), e);
                }
                break;
            case RESET_GOODS_INDEX:
                try {
                    String goodsIdsJsonStr = new String(messageExt.getBody());
                    List<EsGoodsIndex> goodsIndices = JSONUtil.toList(goodsIdsJsonStr, EsGoodsIndex.class);
                    goodsIndexService.updateBulkIndex(goodsIndices);
                } catch (Exception e) {
                    log.error("???????????????????????????????????????????????????: " + new String(messageExt.getBody()), e);
                }
                break;
            //????????????
            case GOODS_AUDIT:
                Goods goods = JSONUtil.toBean(new String(messageExt.getBody()), Goods.class);
                updateGoodsIndex(goods);
                break;
            //????????????
            case GOODS_DELETE:
                try {
                    String goodsIdsJsonStr = new String(messageExt.getBody());
                    for (String goodsId : JSONUtil.toList(goodsIdsJsonStr, String.class)) {
                        goodsIndexService.deleteIndex(MapUtil.builder(new HashMap<String, Object>()).put("goodsId", goodsId).build());
                    }

                    promotionService.removeByGoodsIds(goodsIdsJsonStr);
                } catch (Exception e) {
                    log.error("???????????????????????????????????????????????????: " + new String(messageExt.getBody()), e);
                }
                break;
            case DOWN:
                String goodsIdsJsonStr = new String(messageExt.getBody());
                promotionService.removeByGoodsIds(goodsIdsJsonStr);
                break;
            //????????????
            case SKU_DELETE:
                String message = new String(messageExt.getBody());
                List<String> skuIds = JSONUtil.toList(message, String.class);
                goodsCollectionService.deleteSkuCollection(skuIds);
                break;
            case STORE_GOODS_DELETE:
                try {
                    String storeId = new String(messageExt.getBody());
                    goodsIndexService.deleteIndex(MapUtil.builder(new HashMap<String, Object>()).put("storeId", storeId).build());
                } catch (RetryException re) {
                    throw re;
                } catch (Exception e) {
                    log.error("?????????????????????????????????????????????????????????: " + new String(messageExt.getBody()), e);
                }
                break;
            //????????????
            case GOODS_COMMENT_COMPLETE:
                MemberEvaluation memberEvaluation = JSONUtil.toBean(new String(messageExt.getBody()), MemberEvaluation.class);
                for (GoodsCommentCompleteEvent goodsCommentCompleteEvent : goodsCommentCompleteEvents) {
                    try {
                        goodsCommentCompleteEvent.goodsComment(memberEvaluation);
                    } catch (Exception e) {
                        log.error("??????{},???{}??????????????????????????????????????????", new String(messageExt.getBody()), goodsCommentCompleteEvent.getClass().getName(), e);
                    }
                }
                break;
            //??????????????????
            case BUY_GOODS_COMPLETE:
                this.goodsBuyComplete(messageExt);
                break;
            default:
                log.error("?????????????????????{}", new String(messageExt.getBody()));
                break;
        }
    }

    private void updateGoodsIndexPromotions(String promotionsJsonStr) {
        try {
            log.info("??????????????????????????????: {}", promotionsJsonStr);
            JSONObject jsonObject = JSONUtil.parseObj(promotionsJsonStr);
            // ?????????????????????????????????????????????????????????????????? BasePromotions????????????????????????????????????sdk?????????
            BasePromotions promotions = (BasePromotions) jsonObject.get("promotions", ClassLoaderUtil.loadClass(jsonObject.get("promotionsType").toString()));
            // ??????????????????key,??? ???????????? + ??????id ??????
            String esPromotionKey = jsonObject.get("esPromotionKey").toString();
            if (PromotionsScopeTypeEnum.PORTION_GOODS.name().equals(promotions.getScopeType())) {
                for (int i = 0; ; i++) {
                    PromotionGoodsSearchParams searchParams = new PromotionGoodsSearchParams();
                    searchParams.setPromotionId(promotions.getId());
                    PageVO pageVO = new PageVO();
                    pageVO.setPageNumber(i);
                    pageVO.setPageSize(BATCH_SIZE);
                    Page<PromotionGoods> promotionGoodsPage = this.promotionGoodsService.pageFindAll(searchParams, pageVO);
                    if (promotionGoodsPage == null || promotionGoodsPage.getRecords().isEmpty()) {
                        break;
                    }
                    List<String> skuIds = promotionGoodsPage.getRecords().stream().map(PromotionGoods::getSkuId).collect(Collectors.toList());
                    // ?????????????????????????????????????????????????????????????????????????????????????????????????????????
                    this.goodsIndexService.deleteEsGoodsPromotionByPromotionKey(skuIds, esPromotionKey);
                    this.goodsIndexService.updateEsGoodsIndexByList(promotionGoodsPage.getRecords(), promotions, esPromotionKey);
                }

            } else if (PromotionsScopeTypeEnum.PORTION_GOODS_CATEGORY.name().equals(promotions.getScopeType())) {
                for (int i = 0; ; i++) {
                    GoodsSearchParams searchParams = new GoodsSearchParams();
                    searchParams.setCategoryPath(promotions.getScopeId());
                    searchParams.setPageNumber(i);
                    searchParams.setPageSize(BATCH_SIZE);
                    IPage<GoodsSku> goodsSkuByPage = this.goodsSkuService.getGoodsSkuByPage(searchParams);
                    if (goodsSkuByPage == null || goodsSkuByPage.getRecords().isEmpty()) {
                        break;
                    }
                    List<String> skuIds = goodsSkuByPage.getRecords().stream().map(GoodsSku::getId).collect(Collectors.toList());
                    // ?????????????????????????????????????????????????????????????????????????????????????????????????????????
                    this.goodsIndexService.deleteEsGoodsPromotionByPromotionKey(skuIds, esPromotionKey);
                    this.goodsIndexService.updateEsGoodsIndexPromotions(skuIds, promotions, esPromotionKey);
                }

            } else if (PromotionsScopeTypeEnum.ALL.name().equals(promotions.getScopeType())) {
                this.goodsIndexService.updateEsGoodsIndexAllByList(promotions, esPromotionKey);
            }
        } catch (Exception e) {
            log.error("??????????????????????????????????????????", e);
        }
    }

    /**
     * ??????????????????
     *
     * @param goodsList ??????????????????
     */
    private void updateGoodsIndex(List<Goods> goodsList) {
        for (Goods goods : goodsList) {
            this.updateGoodsIndex(goods);
        }
    }


    /**
     * ??????????????????????????????id
     *
     * @param storeId ??????id
     */
    private void updateGoodsIndex(String storeId) {
        //????????????????????????&&???????????????
        GoodsSearchParams searchParams = new GoodsSearchParams();
        searchParams.setStoreId(storeId);
        for (Goods goods : this.goodsService.queryListByParams(searchParams)) {
            this.updateGoodsIndex(goods);
        }

    }

    /**
     * ??????????????????
     *
     * @param goods ????????????
     */
    private void updateGoodsIndex(Goods goods) {
        for (int i = 1; ; i++) {
            //????????????????????????&&???????????????
            GoodsSearchParams searchParams = new GoodsSearchParams();
            searchParams.setGoodsId(goods.getId());
            searchParams.setPageNumber(i);
            searchParams.setPageSize(BATCH_SIZE);
            IPage<GoodsSku> goodsSkuByPage = this.goodsSkuService.getGoodsSkuByPage(searchParams);
            if (goodsSkuByPage == null || goodsSkuByPage.getRecords().isEmpty()) {
                break;
            }
            log.info("goods???{}", goods);
            log.info("goodsSkuList???{}", goodsSkuByPage.getRecords());
            if (goods.getAuthFlag().equals(GoodsAuthEnum.PASS.name()) && goods.getMarketEnable().equals(GoodsStatusEnum.UPPER.name()) && Boolean.FALSE.equals(goods.getDeleteFlag())) {
                this.generatorGoodsIndex(goods, goodsSkuByPage.getRecords());
            } else {
                //??????????????????????????????es?????????????????????????????????????????????
                for (GoodsSku goodsSku : goodsSkuByPage.getRecords()) {
                    EsGoodsIndex esGoodsOld = goodsIndexService.findById(goodsSku.getId());
                    if (esGoodsOld != null) {
                        goodsIndexService.deleteIndexById(goodsSku.getId());
                    }
                }
            }
        }

    }

    /**
     * ??????????????????
     *
     * @param goods        ????????????
     * @param goodsSkuList ??????sku??????
     */
    private void generatorGoodsIndex(Goods goods, List<GoodsSku> goodsSkuList) {
        int skuSource = 100;
        List<EsGoodsIndex> esGoodsIndices = new ArrayList<>();
        for (GoodsSku goodsSku : goodsSkuList) {
            EsGoodsIndex goodsIndex = this.settingUpGoodsIndexData(goods, goodsSku);
            goodsIndex.setSkuSource(skuSource--);
            log.info("goodsSku???{}", goodsSku);
            log.info("?????????????????? {}", goodsIndex);
            esGoodsIndices.add(goodsIndex);
        }
        this.goodsIndexService.deleteIndex(MapUtil.builder(new HashMap<String, Object>()).put("goodsId", goods.getId()).build());
        this.goodsIndexService.addIndex(esGoodsIndices);
    }

    private EsGoodsIndex settingUpGoodsIndexData(Goods goods, GoodsSku goodsSku) {
        EsGoodsIndex goodsIndex = new EsGoodsIndex(goodsSku);
        if (goods.getParams() != null && !goods.getParams().isEmpty()) {
            List<GoodsParamsDTO> goodsParamDTOS = JSONUtil.toList(goods.getParams(), GoodsParamsDTO.class);
            goodsIndex = new EsGoodsIndex(goodsSku, goodsParamDTOS);
        }
        goodsIndex.setAuthFlag(goods.getAuthFlag());
        goodsIndex.setMarketEnable(goods.getMarketEnable());
        this.settingUpGoodsIndexOtherParam(goodsIndex);
        return goodsIndex;
    }

    /**
     * ??????????????????????????????????????????????????????
     *
     * @param goodsIndex ??????????????????
     */
    private void settingUpGoodsIndexOtherParam(EsGoodsIndex goodsIndex) {
        List<Category> categories = categoryService.listByIdsOrderByLevel(Arrays.asList(goodsIndex.getCategoryPath().split(",")));
        if (!categories.isEmpty()) {
            goodsIndex.setCategoryNamePath(ArrayUtil.join(categories.stream().map(Category::getName).toArray(), ","));
        }
        Brand brand = brandService.getById(goodsIndex.getBrandId());
        if (brand != null) {
            goodsIndex.setBrandName(brand.getName());
            goodsIndex.setBrandUrl(brand.getLogo());
        }
        if (goodsIndex.getStoreCategoryPath() != null && CharSequenceUtil.isNotEmpty(goodsIndex.getStoreCategoryPath())) {
            List<StoreGoodsLabel> storeGoodsLabels = storeGoodsLabelService.listByStoreIds(Arrays.asList(goodsIndex.getStoreCategoryPath().split(",")));
            if (!storeGoodsLabels.isEmpty()) {
                goodsIndex.setStoreCategoryNamePath(ArrayUtil.join(storeGoodsLabels.stream().map(StoreGoodsLabel::getLabelName).toArray(), ","));
            }
        }

        if (goodsIndex.getOriginPromotionMap() == null || goodsIndex.getOriginPromotionMap().isEmpty()) {
            Map<String, Object> goodsCurrentPromotionMap = promotionService.getGoodsSkuPromotionMap(goodsIndex.getStoreId(), goodsIndex.getId());
            goodsIndex.setPromotionMapJson(JSONUtil.toJsonStr(goodsCurrentPromotionMap));
        }
    }


    /**
     * ????????????
     * 1.???????????????????????????
     * 2.???????????????-????????????????????????
     * 3.??????????????????
     *
     * @param goods ??????
     */
    private void deleteGoods(Goods goods) {

        DistributionGoodsSearchParams searchParams = new DistributionGoodsSearchParams();
        searchParams.setGoodsId(goods.getId());
        //????????????????????????
        DistributionGoods distributionGoods = distributionGoodsService.getDistributionGoods(searchParams);

        if (distributionGoods != null) {

            //??????????????????????????????
            distributionSelectedGoodsService.deleteByDistributionGoodsId(distributionGoods.getId());

            //??????????????????
            distributionGoodsService.removeById(distributionGoods.getId());
        }
    }

    /**
     * ??????????????????
     * 1.????????????????????????
     * 2.??????SKU????????????
     * 3.????????????????????????
     *
     * @param messageExt ?????????
     */
    private void goodsBuyComplete(MessageExt messageExt) {
        String goodsCompleteMessageStr = new String(messageExt.getBody());
        List<GoodsCompleteMessage> goodsCompleteMessageList = JSONUtil.toList(JSONUtil.parseArray(goodsCompleteMessageStr), GoodsCompleteMessage.class);
        for (GoodsCompleteMessage goodsCompleteMessage : goodsCompleteMessageList) {
            Goods goods = goodsService.getById(goodsCompleteMessage.getGoodsId());
            if (goods != null) {
                //????????????????????????
                if (goods.getBuyCount() == null) {
                    goods.setBuyCount(0);
                }
                int buyCount = goods.getBuyCount() + goodsCompleteMessage.getBuyNum();
                this.goodsService.updateGoodsBuyCount(goodsCompleteMessage.getGoodsId(), buyCount);
            } else {
                log.error("??????Id???[" + goodsCompleteMessage.getGoodsId() + "??????????????????????????????????????????");
            }
            GoodsSku goodsSku = goodsSkuService.getById(goodsCompleteMessage.getSkuId());
            if (goodsSku != null) {
                //????????????????????????
                if (goodsSku.getBuyCount() == null) {
                    goodsSku.setBuyCount(0);
                }
                int buyCount = goodsSku.getBuyCount() + goodsCompleteMessage.getBuyNum();
                goodsSku.setBuyCount(buyCount);
                goodsSkuService.update(goodsSku);

                this.goodsIndexService.updateIndex(MapUtil.builder(new HashMap<String, Object>()).put("id", goodsCompleteMessage.getSkuId()).build(), MapUtil.builder(new HashMap<String, Object>()).put("buyCount", buyCount).build());

            } else {
                log.error("??????SkuId???[" + goodsCompleteMessage.getGoodsId() + "??????????????????????????????????????????");
            }
        }
    }
}
