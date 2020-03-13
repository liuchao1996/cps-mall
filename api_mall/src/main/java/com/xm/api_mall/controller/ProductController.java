package com.xm.api_mall.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.xm.api_mall.component.PlatformContext;
import com.xm.api_mall.exception.ApiCallException;
import com.xm.api_mall.utils.SentenceUtils;
import com.xm.api_mall.utils.TextToGoodsUtils;
import com.xm.comment.annotation.LoginUser;
import com.xm.comment.annotation.Pid;
import com.xm.comment_feign.module.user.feign.UserFeignClient;
import com.xm.comment_serialize.module.mall.bo.ShareLinkBo;
import com.xm.comment_utils.exception.GlobleException;
import com.xm.comment_utils.response.MsgEnum;
import com.xm.comment_serialize.module.mall.bo.ProductIndexBo;
import com.xm.comment_serialize.module.mall.entity.SmProductEntity;
import com.xm.comment_serialize.module.mall.ex.SmProductEntityEx;
import com.xm.comment_serialize.module.mall.form.GetProductSaleInfoForm;
import com.xm.comment_serialize.module.mall.form.ProductDetailForm;
import com.xm.comment_serialize.module.mall.form.ProductListForm;
import com.xm.comment_serialize.module.mall.vo.SmProductVo;
import com.xm.comment_utils.mybatis.PageBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/product")
public class ProductController {

    @Autowired
    private PlatformContext productContext;
    @Autowired
    private UserFeignClient userFeignClient;

    @Resource(name = "myExecutor")
    private ThreadPoolTaskExecutor executor;
    /**
     * 商品列表
     * @return
     */
    @PostMapping("/list")
    public Object getProductList(@RequestBody @Valid ProductListForm productListForm, BindingResult bindingResult, @LoginUser(necessary = false) Integer userId,@Pid(necessary = false) String pid) throws Exception {
        PageBean<SmProductEntityEx> pageBean = (PageBean<SmProductEntityEx>) productContext
                .platformType(productListForm.getPlatformType())
                .listType(productListForm.getListType())
                .invoke(
                        userId,
                        pid,
                        productListForm);
        List<SmProductVo> list = pageBean.getList().stream().map(o->{
            SmProductVo smProductVo = new SmProductVo();
            BeanUtil.copyProperties(o,smProductVo);
            return smProductVo;
        }).collect(Collectors.toList());
        PageBean<SmProductVo> productVoPageBean = new PageBean<>();
        productVoPageBean.setList(list);
        productVoPageBean.setPageNum(pageBean.getPageNum());
        productVoPageBean.setPageSize(pageBean.getPageSize());
        productVoPageBean.setTotal(pageBean.getTotal());
        return productVoPageBean;
    }

    /**
     * 获取商品详情
     * @return
     */
    @GetMapping("/detail")
    public SmProductVo getProductDetail(@Valid ProductDetailForm productDetailForm, BindingResult bindingResult, @LoginUser(necessary = false) Integer userId,@Pid(necessary = false) String pid) throws Exception {
        return getDetailVo(userId,pid,productDetailForm.getPlatformType(),productDetailForm.getGoodsId(),productDetailForm.getShareUserId());
    }

    private SmProductVo getDetailVo(Integer userId,String pid,Integer platformType,String goodsId,Integer shareUserId) throws Exception {
        SmProductEntityEx smProductEntityEx = productContext
                .platformType(platformType)
                .getService()
                .detail(
                        goodsId,
                        pid,
                        userId,
                        shareUserId);
        SmProductVo smProductVo = new SmProductVo();
        BeanUtil.copyProperties(smProductEntityEx,smProductVo);
        return smProductVo;
    }

    /**
     * 批量获取商品详情
     * @return
     */
    @GetMapping("/details")
    public List<SmProductEntity> getProductDetails(Integer platformType,@RequestParam("goodsIds") List<String> goodsIds) throws Exception {
        return productContext
                .platformType(platformType)
                .getService()
                .details(goodsIds);
    }

    /**
     * 批量获取商品详情
     * @return
     */
    @PostMapping("/details")
    public List<SmProductEntity> getProductDetails(@RequestBody List<ProductIndexBo> productIndexBos) throws Exception {
        Map<Integer, List<ProductIndexBo>> group = productIndexBos.stream().collect(Collectors.groupingBy(ProductIndexBo::getPlatformType));
        List<Future<List<SmProductEntity>>> futures = new ArrayList<>();
        for (Map.Entry<Integer, List<ProductIndexBo>> integerListEntry : group.entrySet()) {
            Future<List<SmProductEntity>> listFuture = executor.submit(new Callable<List<SmProductEntity>>() {
                @Override
                public List<SmProductEntity> call() throws Exception {
                    return productContext
                            .platformType(integerListEntry.getKey())
                            .getService()
                            .details(integerListEntry.getValue().stream().map(ProductIndexBo::getGoodsId).collect(Collectors.toList()));
                }
            });
            futures.add(listFuture);
        }
        //合并结果
        List<SmProductEntity> result = new ArrayList<>();
        futures.forEach(o ->{
            try {
                List<SmProductEntity> smProductEntities = o.get();
                result.addAll(smProductEntities);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        });
        return result;
    }

    @GetMapping("/sale")
    public ShareLinkBo getProductSaleInfo(@LoginUser Integer userId, @Pid(necessary = false) String pid, @Valid GetProductSaleInfoForm productSaleInfoForm) throws Exception {
        if(userId.equals(productSaleInfoForm.getShareUserId()))
            productSaleInfoForm.setShareUserId(null);
        return productContext
                .platformType(productSaleInfoForm.getPlatformType())
                .getService()
                .saleInfo(
                        userId,
                        pid,
                        productSaleInfoForm);
    }

    @GetMapping("/url/parse")
    public TextToGoodsUtils.GoodsSpec parseUrl(@LoginUser(necessary = false) Integer userId,@Pid(necessary = false) String pid,String url) throws Exception {
        if(StrUtil.isBlank(url))
            throw new GlobleException(MsgEnum.PARAM_VALID_ERROR);
        TextToGoodsUtils.GoodsSpec goodsSpec = TextToGoodsUtils.parse(url);
        switch (goodsSpec.getParseType()){
            case 1:{
                try {
                    goodsSpec.setGoodsInfo(getDetailVo(userId,pid,goodsSpec.getPlatformType(),goodsSpec.getGoodsId(),null));
                }catch (ApiCallException e){
                    goodsSpec.setParseType(2);
                    goodsSpec.setSimpleInfo(productContext
                            .platformType(goodsSpec.getPlatformType())
                            .getService().basicDetail(Long.valueOf(goodsSpec.getGoodsId())));
                }
            }
        }
        return goodsSpec;
    }
}
