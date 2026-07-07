package com.dayworks_ltd.loyalty_engine.liquor.repository.adapter;

import com.dayworks_ltd.loyalty_engine.credit_engine.model.CanonicalLiquorProduct;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.liquor.repository.port.LiquorDAO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class LiquorDAOAdapter implements LiquorDAO {

    private final Logger logger = LoggerFactory.getLogger(LiquorDAO.class);
    private final EntityManager entityManager;

    public LiquorDAOAdapter(
            EntityManager entityManager
    ) {
        this.entityManager = entityManager;
    }

    private EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public List<Inventory> getInventoryStockMatchingName(Long merchantId, String itemNameWhereClause) {
//        String sql = " SELECT * " +
//                " FROM canonical_liquor_products " +
//                " WHERE canonical_name like '%" + name + "%' ";

        String sql = " SELECT * " +
                " FROM inventory_stock " +
                " WHERE merchant_id = " + merchantId + " " +
                " " + itemNameWhereClause + " ";

        Query query = getEntityManager().createNativeQuery(sql, Inventory.class);

        return query.getResultList();
    }
}
