package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Creates the demo supermarket "ASANO Fresh Mart" (with its "WhatsApp customers" buyer account) and a
 * diverse Kenyan grocery catalogue. Runs only when demo.fresh-mart.seed=true; safe to run again (it
 * only adds what is missing and never touches existing stock).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FreshMartSeeder implements ApplicationRunner {

    public static final String STORE_NAME = "ASANO Fresh Mart";
    public static final String CUSTOMERS_SUFFIX = " - WhatsApp customers";

    private final MerchantRepository merchantRepository;
    private final InventoryRepository inventoryRepository;

    @Value("${demo.fresh-mart.seed:false}")
    private boolean seed;

    /** Phone for the store's owner SMS codes (stock reports, prices). */
    @Value("${demo.fresh-mart.phone:0708613719}")
    private String storePhone;

    /** name, brand, category, retail price KES, stock, keywords (what customers type). */
    record P(String name, String brand, String category, int price, int stock, String keywords) {}

    static final List<P> CATALOG = List.of(
            // Unga & Flour
            new P("Jogoo Maize Flour 2kg", "Jogoo", "Unga & Flour", 230, 80, "unga, ugali, maize meal, flour"),
            new P("Jogoo Maize Flour 1kg", "Jogoo", "Unga & Flour", 125, 60, "unga, ugali, maize meal"),
            new P("Soko Maize Flour 2kg", "Soko", "Unga & Flour", 220, 70, "unga, ugali, maize meal"),
            new P("Pembe Maize Flour 2kg", "Pembe", "Unga & Flour", 225, 50, "unga, ugali, maize meal"),
            new P("Ajab Home Baking Flour 2kg", "Ajab", "Unga & Flour", 250, 40, "unga ngano, wheat flour, baking flour"),
            new P("Exe Chapati Flour 2kg", "Exe", "Unga & Flour", 260, 45, "unga ngano, chapati flour, wheat"),
            new P("Kensalt Iodised Salt 1kg", "Kensalt", "Unga & Flour", 50, 90, "chumvi, salt"),
            // Rice & Cereals
            new P("Mwea Pishori Rice 2kg", "Mwea", "Rice & Cereals", 480, 40, "mchele, pishori, rice"),
            new P("Sindano Rice 2kg", "Sindano", "Rice & Cereals", 320, 40, "mchele, rice"),
            new P("Daawat Basmati Rice 1kg", "Daawat", "Rice & Cereals", 420, 25, "mchele, basmati, rice"),
            new P("Santa Lucia Spaghetti 400g", "Santa Lucia", "Rice & Cereals", 130, 50, "spaghetti, pasta, tambi"),
            new P("Weetabix 430g", "Weetabix", "Rice & Cereals", 420, 20, "cereal, breakfast"),
            new P("Quaker Oats 500g", "Quaker", "Rice & Cereals", 330, 25, "oats, uji"),
            new P("Rosecoco Beans 1kg", "", "Rice & Cereals", 210, 35, "maharagwe, beans"),
            new P("Green Grams Ndengu 1kg", "", "Rice & Cereals", 220, 30, "ndengu, green grams, pojo"),
            // Sugar, Tea & Coffee
            new P("Mumias Sugar 1kg", "Mumias", "Sugar, Tea & Coffee", 190, 90, "sukari, sugar"),
            new P("Kabras Sugar 2kg", "Kabras", "Sugar, Tea & Coffee", 370, 50, "sukari, sugar"),
            new P("Ketepa Pride Tea Leaves 100g", "Ketepa", "Sugar, Tea & Coffee", 85, 60, "majani chai, tea leaves, chai"),
            new P("Kericho Gold Tea Bags 50s", "Kericho Gold", "Sugar, Tea & Coffee", 180, 30, "chai, tea bags"),
            new P("Nescafe Classic 50g", "Nescafe", "Sugar, Tea & Coffee", 290, 25, "kahawa, coffee"),
            new P("Cadbury Drinking Chocolate 200g", "Cadbury", "Sugar, Tea & Coffee", 420, 15, "cocoa, chocolate drink"),
            new P("Royco Mchuzi Mix 200g", "Royco", "Sugar, Tea & Coffee", 140, 40, "royco, mchuzi mix, spice"),
            // Milk & Dairy
            new P("Brookside Whole Milk 500ml", "Brookside", "Milk & Dairy", 65, 100, "maziwa, milk"),
            new P("Brookside Whole Milk 1L", "Brookside", "Milk & Dairy", 125, 60, "maziwa, milk"),
            new P("Tuzo Fresh Milk 500ml", "Tuzo", "Milk & Dairy", 63, 80, "maziwa, milk"),
            new P("KCC Mala 500ml", "KCC", "Milk & Dairy", 75, 40, "mala, maziwa lala, fermented milk"),
            new P("Brookside UHT Long Life Milk 500ml", "Brookside", "Milk & Dairy", 75, 50, "maziwa, long life milk, uht"),
            new P("Brookside Strawberry Yoghurt 500ml", "Brookside", "Milk & Dairy", 160, 30, "yoghurt, yogurt"),
            new P("Blue Band Margarine 500g", "Blue Band", "Milk & Dairy", 290, 35, "blueband, margarine, siagi"),
            new P("Eggs Tray 30", "", "Milk & Dairy", 450, 25, "mayai, eggs, tray"),
            // Bread & Bakery
            new P("Supaloaf White Bread 400g", "Supaloaf", "Bread & Bakery", 65, 50, "mkate, bread"),
            new P("Festive White Bread 600g", "Festive", "Bread & Bakery", 90, 40, "mkate, bread"),
            new P("Broadways Brown Bread 400g", "Broadways", "Bread & Bakery", 70, 30, "mkate brown, brown bread"),
            new P("Festive Brown Bread 400g", "Festive", "Bread & Bakery", 70, 30, "mkate brown, brown bread"),
            new P("Manji Digestive Biscuits 500g", "Manji", "Bread & Bakery", 280, 30, "biscuits, digestive"),
            // Cooking Oil & Fats
            new P("Elianto Corn Oil 1L", "Elianto", "Cooking Oil & Fats", 380, 40, "mafuta, cooking oil, oil"),
            new P("Elianto Corn Oil 3L", "Elianto", "Cooking Oil & Fats", 1050, 20, "mafuta, cooking oil, oil"),
            new P("Fresh Fri Cooking Oil 1L", "Fresh Fri", "Cooking Oil & Fats", 330, 45, "mafuta, cooking oil, fresh fry"),
            new P("Fresh Fri Cooking Oil 3L", "Fresh Fri", "Cooking Oil & Fats", 950, 20, "mafuta, cooking oil, fresh fry"),
            new P("Golden Fry Cooking Oil 2L", "Golden Fry", "Cooking Oil & Fats", 620, 25, "mafuta, cooking oil"),
            new P("Kasuku Cooking Fat 1kg", "Kasuku", "Cooking Oil & Fats", 330, 30, "kimbo, fat, mafuta ya kupika"),
            // Drinks
            new P("Coca-Cola 500ml", "Coca-Cola", "Drinks", 80, 120, "coke, soda"),
            new P("Coca-Cola 2L", "Coca-Cola", "Drinks", 220, 40, "coke, soda"),
            new P("Fanta Orange 500ml", "Fanta", "Drinks", 80, 80, "fanta, soda"),
            new P("Sprite 500ml", "Sprite", "Drinks", 80, 80, "sprite, soda"),
            new P("Krest Bitter Lemon 500ml", "Krest", "Drinks", 80, 40, "krest, bitter lemon, soda"),
            new P("Keringet Water 1L", "Keringet", "Drinks", 80, 90, "maji, water"),
            new P("Dasani Water 500ml", "Dasani", "Drinks", 50, 100, "maji, water"),
            new P("Afia Mango Juice 1L", "Afia", "Drinks", 210, 40, "juice, juisi, mango"),
            new P("Minute Maid Mango 400ml", "Minute Maid", "Drinks", 90, 50, "juice, juisi, mango"),
            new P("Del Monte Orange Juice 1L", "Del Monte", "Drinks", 330, 25, "juice, juisi, orange"),
            new P("Red Bull 250ml", "Red Bull", "Drinks", 230, 40, "redbull, energy drink"),
            new P("Tusker Lager 500ml", "Tusker", "Drinks", 280, 60, "tusker, beer, pombe"),
            new P("Guinness 500ml", "Guinness", "Drinks", 300, 40, "guinness, beer, pombe"),
            // Household & Cleaning
            new P("Omo Washing Powder 1kg", "Omo", "Household & Cleaning", 390, 40, "omo, sabuni ya kufua, washing powder"),
            new P("Ariel Washing Powder 1kg", "Ariel", "Household & Cleaning", 420, 30, "ariel, sabuni ya kufua, washing powder"),
            new P("Menengai Bar Soap 800g", "Menengai", "Household & Cleaning", 210, 40, "sabuni ya mche, bar soap"),
            new P("Jik Bleach 750ml", "Jik", "Household & Cleaning", 190, 35, "jik, bleach"),
            new P("Harpic Toilet Cleaner 500ml", "Harpic", "Household & Cleaning", 330, 25, "harpic, toilet cleaner"),
            new P("Vim Scouring Powder 500g", "Vim", "Household & Cleaning", 120, 30, "vim, scouring powder"),
            new P("Softcare Toilet Paper 10 Pack", "Softcare", "Household & Cleaning", 450, 30, "tissue, toilet paper, karatasi ya choo"),
            new P("Dettol Antiseptic Liquid 500ml", "Dettol", "Household & Cleaning", 640, 20, "dettol, antiseptic"),
            new P("Doom Insect Spray 300ml", "Doom", "Household & Cleaning", 450, 20, "doom, dawa ya mende, insect killer"),
            new P("Matchbox 10 Pack", "", "Household & Cleaning", 50, 50, "kiberiti, matches"),
            new P("Garbage Bags 20 Pack", "", "Household & Cleaning", 150, 30, "bin bags, garbage bags"),
            // Personal Care
            new P("Colgate Toothpaste 140g", "Colgate", "Personal Care", 230, 40, "dawa ya meno, toothpaste"),
            new P("Geisha Bath Soap 250g", "Geisha", "Personal Care", 120, 50, "sabuni ya kuoga, bathing soap"),
            new P("Dettol Bath Soap 175g", "Dettol", "Personal Care", 150, 40, "sabuni ya kuoga, bathing soap"),
            new P("Nivea Body Lotion 400ml", "Nivea", "Personal Care", 650, 20, "lotion, mafuta ya mwili"),
            new P("Vaseline Petroleum Jelly 250ml", "Vaseline", "Personal Care", 300, 25, "vaseline, jelly"),
            new P("Rexona Roll-On 50ml", "Rexona", "Personal Care", 330, 25, "roll on, deodorant"),
            new P("Always Sanitary Pads 8s", "Always", "Personal Care", 120, 40, "pads, always"),
            new P("Gillette Blue II Razors 5 Pack", "Gillette", "Personal Care", 300, 20, "wembe, razor"),
            // Baby & Snacks
            new P("Pampers Baby Dry Size 4 44s", "Pampers", "Baby & Snacks", 1650, 15, "pampers, diapers, nappies"),
            new P("Huggies Dry Size 3 40s", "Huggies", "Baby & Snacks", 1450, 15, "huggies, diapers, nappies"),
            new P("Cerelac Wheat 400g", "Nestle", "Baby & Snacks", 750, 15, "cerelac, baby cereal, uji wa mtoto"),
            new P("Zesta Peanut Butter 400g", "Zesta", "Baby & Snacks", 380, 20, "peanut butter, siagi ya karanga"),
            new P("Cadbury Dairy Milk 80g", "Cadbury", "Baby & Snacks", 200, 40, "chocolate"),
            new P("Tropical Heat Crisps 200g", "Tropical Heat", "Baby & Snacks", 270, 30, "crisps, chips"),
            new P("Zesta Strawberry Jam 500g", "Zesta", "Baby & Snacks", 360, 20, "jam"));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seed) return;
        Merchant store = merchantRepository.findByBusinessNameContainingIgnoreCase(STORE_NAME).stream()
                .filter(m -> STORE_NAME.equalsIgnoreCase(m.getBusinessName())).findFirst()
                .orElseGet(() -> merchantRepository.save(merchant(STORE_NAME, "SUPERMARKET", null)));
        String customersName = STORE_NAME + CUSTOMERS_SUFFIX;
        Merchant customers = merchantRepository.findByBusinessNameContainingIgnoreCase(customersName).stream()
                .filter(m -> customersName.equalsIgnoreCase(m.getBusinessName())).findFirst()
                .orElseGet(() -> merchantRepository.save(merchant(customersName, "WHATSAPP_CUSTOMERS", store)));

        String storeId = String.valueOf(store.getId());
        int added = 0;
        for (P p : CATALOG) {
            String code = storeId + "-" + p.name().toUpperCase().replaceAll("[^A-Z0-9]", "");
            if (inventoryRepository.findByMerchantIdAndItemCode(storeId, code).isPresent()) continue;
            BigDecimal price = BigDecimal.valueOf(p.price());
            inventoryRepository.save(Inventory.builder()
                    .merchantId(storeId)
                    .itemName(p.name())
                    .itemCode(code)
                    .productCategory(p.category())
                    .productBrand(p.brand().isEmpty() ? null : p.brand())
                    .keywords(p.keywords())
                    .unitPrice(price)
                    .unitCost(price.multiply(new BigDecimal("0.82")).setScale(0, RoundingMode.HALF_UP))
                    .startingStock(p.stock()).addedStock(0).soldStock(0)
                    .availableStock(p.stock()).closingStock(p.stock())
                    .totalSales(BigDecimal.ZERO).grossSales(BigDecimal.ZERO)
                    .netlSales(BigDecimal.ZERO).deductions(BigDecimal.ZERO)
                    .isActive(true).metaSynced(false).reorderLevel(10)
                    .supplierName("Demo stock")
                    .recordDate(LocalDate.now()).lastUpdated(LocalDateTime.now())
                    .build());
            added++;
        }
        log.info("Fresh Mart demo: store id {}, customers account id {}, {} products added ({} in catalogue)",
                store.getId(), customers.getId(), added, CATALOG.size());
    }

    private Merchant merchant(String name, String type, Merchant distributor) {
        return Merchant.builder()
                .businessName(name)
                .businessType(type)
                .businessPhone(storePhone)
                .merchantOtp("")
                .location("Kakamega")
                .createdAt(LocalDateTime.now())
                .metaConnected(false)
                .metaSyncEnabled(false)
                .distributor(distributor)
                .build();
    }
}
