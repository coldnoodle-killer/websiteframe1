package cn.lili.modules.wallet.serviceimpl;


import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.utils.CurrencyUtil;
import cn.lili.common.utils.SnowFlake;
import cn.lili.common.utils.StringUtils;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.payment.entity.enums.PaymentMethodEnum;
import cn.lili.modules.payment.kit.CashierSupport;
import cn.lili.modules.system.entity.dos.Setting;
import cn.lili.modules.system.entity.dto.WithdrawalSetting;
import cn.lili.modules.system.entity.enums.SettingEnum;
import cn.lili.modules.system.service.SettingService;
import cn.lili.modules.wallet.entity.dos.MemberWallet;
import cn.lili.modules.wallet.entity.dos.MemberWithdrawApply;
import cn.lili.modules.wallet.entity.dos.WalletLog;
import cn.lili.modules.wallet.entity.dto.MemberWalletUpdateDTO;
import cn.lili.modules.wallet.entity.dto.MemberWithdrawalMessage;
import cn.lili.modules.wallet.entity.enums.DepositServiceTypeEnum;
import cn.lili.modules.wallet.entity.enums.MemberWithdrawalDestinationEnum;
import cn.lili.modules.wallet.entity.enums.WithdrawStatusEnum;
import cn.lili.modules.wallet.entity.vo.MemberWalletVO;
import cn.lili.modules.wallet.mapper.MemberWalletMapper;
import cn.lili.modules.wallet.service.MemberWalletService;
import cn.lili.modules.wallet.service.MemberWithdrawApplyService;
import cn.lili.modules.wallet.service.WalletLogService;
import cn.lili.rocketmq.RocketmqSendCallbackBuilder;
import cn.lili.rocketmq.tags.MemberTagsEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;


/**
 * ???????????????????????????
 *
 * @author pikachu
 * @since 2020-02-25 14:10:16
 */
@Service
public class MemberWalletServiceImpl extends ServiceImpl<MemberWalletMapper, MemberWallet> implements MemberWalletService {

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;

    /**
     * ???????????????
     */
    @Autowired
    private WalletLogService walletLogService;
    /**
     * ??????
     */
    @Autowired
    private SettingService settingService;
    /**
     * ??????
     */
    @Autowired
    private MemberService memberService;
    /**
     * ??????????????????
     */
    @Autowired
    private MemberWithdrawApplyService memberWithdrawApplyService;
    @Autowired
    private CashierSupport cashierSupport;

    @Override
    public MemberWalletVO getMemberWallet(String memberId) {
        //??????????????????
        QueryWrapper<MemberWallet> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("member_id", memberId);
        //????????????
        MemberWallet memberWallet = this.getOne(queryWrapper, false);
        //????????????????????????????????????
        if (memberWallet == null) {
            memberWallet = this.save(memberId, memberService.getById(memberId).getUsername());
        }
        //??????????????????
        return new MemberWalletVO(memberWallet.getMemberWallet(), memberWallet.getMemberFrozenWallet());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean increaseWithdrawal(MemberWalletUpdateDTO memberWalletUpdateDTO) {
        //??????????????????????????????????????????????????????????????????
        MemberWallet memberWallet = this.checkMemberWallet(memberWalletUpdateDTO.getMemberId());
        //????????????
        memberWallet.setMemberWallet(CurrencyUtil.add(memberWallet.getMemberWallet(), memberWalletUpdateDTO.getMoney()));
        memberWallet.setMemberFrozenWallet(CurrencyUtil.sub(memberWallet.getMemberFrozenWallet(), memberWalletUpdateDTO.getMoney()));
        this.updateById(memberWallet);
        //?????????????????????
        WalletLog walletLog = new WalletLog(memberWallet.getMemberName(), memberWalletUpdateDTO);
        walletLogService.save(walletLog);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean increase(MemberWalletUpdateDTO memberWalletUpdateDTO) {
        //??????????????????????????????????????????????????????????????????
        MemberWallet memberWallet = this.checkMemberWallet(memberWalletUpdateDTO.getMemberId());
        //???????????????
        memberWallet.setMemberWallet(CurrencyUtil.add(memberWallet.getMemberWallet(), memberWalletUpdateDTO.getMoney()));
        this.baseMapper.updateById(memberWallet);
        //?????????????????????
        WalletLog walletLog = new WalletLog(memberWallet.getMemberName(), memberWalletUpdateDTO);
        walletLogService.save(walletLog);
        return true;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean reduce(MemberWalletUpdateDTO memberWalletUpdateDTO) {
        //??????????????????????????????????????????????????????????????????
        MemberWallet memberWallet = this.checkMemberWallet(memberWalletUpdateDTO.getMemberId());
        //?????????????????????????????? ???????????????????????????
        if (0 > CurrencyUtil.sub(memberWallet.getMemberWallet(), memberWalletUpdateDTO.getMoney())) {
            return false;
        }
        memberWallet.setMemberWallet(CurrencyUtil.sub(memberWallet.getMemberWallet(), memberWalletUpdateDTO.getMoney()));
        //????????????
        this.updateById(memberWallet);
        //?????????????????????
        WalletLog walletLog = new WalletLog(memberWallet.getMemberName(), memberWalletUpdateDTO, true);
        walletLogService.save(walletLog);
        return true;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean reduceWithdrawal(MemberWalletUpdateDTO memberWalletUpdateDTO) {
        //??????????????????????????????????????????????????????????????????
        MemberWallet memberWallet = this.checkMemberWallet(memberWalletUpdateDTO.getMemberId());
        //?????????????????????????????? ???????????????????????????
        if (0 > CurrencyUtil.sub(memberWallet.getMemberWallet(), memberWalletUpdateDTO.getMoney())) {
            throw new ServiceException(ResultCode.WALLET_WITHDRAWAL_INSUFFICIENT);
        }
        memberWallet.setMemberWallet(CurrencyUtil.sub(memberWallet.getMemberWallet(), memberWalletUpdateDTO.getMoney()));
        memberWallet.setMemberFrozenWallet(CurrencyUtil.add(memberWallet.getMemberFrozenWallet(), memberWalletUpdateDTO.getMoney()));
        //????????????
        this.updateById(memberWallet);
        //?????????????????????
        WalletLog walletLog = new WalletLog(memberWallet.getMemberName(), memberWalletUpdateDTO, true);
        walletLogService.save(walletLog);
        return true;

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean reduceFrozen(MemberWalletUpdateDTO memberWalletUpdateDTO) {
        //??????????????????????????????????????????????????????????????????
        MemberWallet memberWallet = this.checkMemberWallet(memberWalletUpdateDTO.getMemberId());
        //???????????????????????????????????????
        if (0 > CurrencyUtil.sub(memberWallet.getMemberFrozenWallet(), memberWalletUpdateDTO.getMoney())) {
            throw new ServiceException(ResultCode.WALLET_WITHDRAWAL_FROZEN_AMOUNT_INSUFFICIENT);
        }
        memberWallet.setMemberFrozenWallet(CurrencyUtil.sub(memberWallet.getMemberFrozenWallet(), memberWalletUpdateDTO.getMoney()));
        this.updateById(memberWallet);
        //?????????????????????
        WalletLog walletLog = new WalletLog(memberWallet.getMemberName(), memberWalletUpdateDTO, true);
        walletLogService.save(walletLog);
        return true;
    }

    /**
     * ????????????????????????????????????????????????????????????
     *
     * @param memberId ??????id
     */
    private MemberWallet checkMemberWallet(String memberId) {
        //???????????????????????????
        MemberWallet memberWallet = this.getOne(new QueryWrapper<MemberWallet>().eq("member_id", memberId), false);
        //????????????????????????????????????????????????????????????????????????
        if (memberWallet == null) {
            Member member = memberService.getById(memberId);
            if (member != null) {
                memberWallet = this.save(memberId, member.getUsername());
            } else {
                throw new ServiceException(ResultCode.USER_AUTHORITY_ERROR);
            }
        }
        return memberWallet;
    }

    @Override
    public void setMemberWalletPassword(Member member, String password) {
        //?????????????????????
        String pwd = new BCryptPasswordEncoder().encode(password);
        //?????????????????????????????????
        QueryWrapper<MemberWallet> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("member_id", member.getId());
        MemberWallet memberWallet = this.getOne(queryWrapper);
        //?????? ???????????????????????? ??????????????????
        if (memberWallet != null) {
            memberWallet.setWalletPassword(pwd);
            this.updateById(memberWallet);
        }
    }


    @Override
    public Boolean checkPassword() {
        //????????????????????????
        AuthUser authUser = UserContext.getCurrentUser();
        //??????????????????
        QueryWrapper<MemberWallet> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("member_id", authUser.getId());
        MemberWallet wallet = this.getOne(queryWrapper);
        return wallet != null && !StringUtils.isEmpty(wallet.getWalletPassword());
    }

    @Override
    public MemberWallet save(String memberId, String memberName) {
        //???????????????????????????
        MemberWallet memberWallet = this.getOne(new QueryWrapper<MemberWallet>().eq("member_id", memberId));
        if (memberWallet != null) {
            return memberWallet;
        }
        memberWallet = new MemberWallet();
        memberWallet.setMemberId(memberId);
        memberWallet.setMemberName(memberName);
        memberWallet.setMemberWallet(0D);
        memberWallet.setMemberFrozenWallet(0D);
        this.save(memberWallet);
        return memberWallet;
    }

    /**
     * ????????????
     * 1?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     * 2??????????????????????????? ??????????????????????????????
     *
     * @param price ????????????
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean applyWithdrawal(Double price) {
        MemberWithdrawalMessage memberWithdrawalMessage = new MemberWithdrawalMessage();
        AuthUser authUser = UserContext.getCurrentUser();
        //??????????????????
        MemberWithdrawApply memberWithdrawApply = new MemberWithdrawApply();
        memberWithdrawApply.setMemberId(authUser.getId());
        memberWithdrawApply.setMemberName(authUser.getNickName());
        memberWithdrawApply.setApplyMoney(price);
        memberWithdrawApply.setApplyStatus(WithdrawStatusEnum.APPLY.name());
        memberWithdrawApply.setSn("W" + SnowFlake.getId());
        //????????????????????????????????????,????????????????????? ?????????????????????
        Setting setting = settingService.get(SettingEnum.WITHDRAWAL_SETTING.name());
        if (setting != null) {
            //??????????????????????????????????????????
            WithdrawalSetting withdrawalSetting = new Gson().fromJson(setting.getSettingValue(), WithdrawalSetting.class);
            if (Boolean.FALSE.equals(withdrawalSetting.getApply())) {
                memberWithdrawApply.setApplyStatus(WithdrawStatusEnum.VIA_AUDITING.name());
                memberWithdrawApply.setInspectRemark("????????????????????????");
                //????????????????????????????????????????????????????????????????????????????????????
                MemberWalletVO memberWalletVO = this.getMemberWallet(memberWithdrawApply.getMemberId());
                if (memberWalletVO.getMemberWallet() < price) {
                    throw new ServiceException(ResultCode.WALLET_WITHDRAWAL_INSUFFICIENT);
                }
                memberWithdrawalMessage.setStatus(WithdrawStatusEnum.VIA_AUDITING.name());
                //??????????????????
                Boolean result = withdrawal(memberWithdrawApply);
                if (Boolean.TRUE.equals(result)) {
                    this.reduce(new MemberWalletUpdateDTO(price, authUser.getId(), "??????????????????", DepositServiceTypeEnum.WALLET_WITHDRAWAL.name()));
                }
            } else {
                memberWithdrawalMessage.setStatus(WithdrawStatusEnum.APPLY.name());
                //???????????????????????????
                this.reduceWithdrawal(new MemberWalletUpdateDTO(price, authUser.getId(), "?????????????????????????????????????????????", DepositServiceTypeEnum.WALLET_WITHDRAWAL.name()));
            }
            //??????????????????????????????

            memberWithdrawalMessage.setMemberId(authUser.getId());
            memberWithdrawalMessage.setPrice(price);
            memberWithdrawalMessage.setDestination(MemberWithdrawalDestinationEnum.WECHAT.name());
            String destination = rocketmqCustomProperties.getMemberTopic() + ":" + MemberTagsEnum.MEMBER_WITHDRAWAL.name();
            rocketMQTemplate.asyncSend(destination, memberWithdrawalMessage, RocketmqSendCallbackBuilder.commonCallback());
        }
        return memberWithdrawApplyService.save(memberWithdrawApply);
    }

    @Override
    public Boolean withdrawal(MemberWithdrawApply memberWithdrawApply) {
        memberWithdrawApply.setInspectTime(new Date());
        //??????????????????????????????
        this.memberWithdrawApplyService.saveOrUpdate(memberWithdrawApply);
        //TODO ??????????????????
        cashierSupport.transfer(PaymentMethodEnum.WECHAT,memberWithdrawApply);
        boolean result = true;
        //???????????????????????? ??????????????? ????????????
        if (!result) {
            throw new ServiceException(ResultCode.WALLET_ERROR_INSUFFICIENT);
        }
        return result;
    }

}