package com.hyoguoo.paymentplatform;

import com.hyoguoo.paymentplatform.core.test.BaseIntegrationTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;

@AutoConfigureMockMvc
@Sql(scripts = "/data-test.sql")
public abstract class IntegrationTest extends BaseIntegrationTest {

}
