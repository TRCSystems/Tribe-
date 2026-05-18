package com.dayworks_ltd.loyalty_engine.merchants;
import com.dayworks_ltd.loyalty_engine.auth.enums.Status;
import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.dto.CreateMerchantRequest;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
@Service
@Transactional

public class MerchantService {

    private final MerchantRepository merchantRepository;
    @Autowired
    private BCryptPasswordEncoder bCryptPasswordEncoder;

    @Autowired
    private UserRepository userRepository;

    public MerchantService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    public String getMerchantOtp(String merchantId)
    {
        Optional<Merchant> merchantOptional = merchantRepository.findById(Long.parseLong(merchantId));

        return merchantOptional.isPresent() ? merchantOptional.get().getMerchantOtp() : null;
    }

    public void updateMerchantOtp(String merchantId, String newGeneratedOtp)
    {
        Optional<Merchant> merchantOptional = merchantRepository.findById(Long.parseLong(merchantId));

        if(merchantOptional.isPresent())
        {
            Merchant merchant = merchantOptional.get();
            merchant.setMerchantOtp(newGeneratedOtp);
            merchantRepository.save(merchant);
        }
    }
    @Transactional
    public Merchant createMerchant(CreateMerchantRequest request) {
        // map to entity
        Merchant merchant = new Merchant();
        merchant.setBusinessName(request.getBusinessName());
        merchant.setLocation(request.getLocation());
        merchant.setTillNumber(request.getTillNumber());
        merchant.setBusinessType(request.getBusinessType());
        merchant.setBusinessPhone(request.getBusinessPhone());

        Merchant saved = merchantRepository.save(merchant);

        // create linked user
        User user = User.builder()
                .username(request.getBusinessName())
                .password(bCryptPasswordEncoder.encode(request.getBusinessName() + "123"))
                .role(UserRole.MERCHANT)
                .status(Status.ACTIVE)
                .isWholesaler(request.getIsWholesaler())
                .subscriptionPlan(request.getSubscriptionPlan())
                .merchant(saved)
                .build();

        userRepository.save(user);

        return saved;
    }

//    public Merchant createMerchant(@Valid CreateMerchantRequest merchant) {
//        if (merchantRepository.existsByTillNumber(merchant.getTillNumber())) {
//            throw new IllegalArgumentException("Merchant with till " + merchant.getTillNumber() + " already exists");
//        }
//        return merchantRepository.save(merchant);
//    }

    public List<Merchant> getAllMerchants() {
        return merchantRepository.findAll();
    }

    public Optional<Merchant> getMerchantById(Long id) {
        return merchantRepository.findById(id);
    }

    public Optional<Merchant> getMerchantByTillNumber(String tillNumber) {
        return merchantRepository.findByTillNumber(tillNumber);
    }

    public Merchant updateMerchant(Long id, Merchant updatedMerchant) {
        return merchantRepository.findById(id).map(existing -> {
            existing.setBusinessName(updatedMerchant.getBusinessName());
            existing.setLocation(updatedMerchant.getLocation());
            existing.setBusinessType(updatedMerchant.getBusinessType());
            existing.setTillNumber(updatedMerchant.getTillNumber());
            return merchantRepository.save(existing);
        }).orElseThrow(() -> new IllegalArgumentException("Merchant not found"));
    }

    public void deleteMerchant(Long id) {
        merchantRepository.deleteById(id);
    }

}
