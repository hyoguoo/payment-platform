package com.hyoguoo.paymentplatform.product.support

import org.testcontainers.containers.MySQLContainer

/**
 * 코틀린은 자기 자신을 타입 인자로 받는 `MySQLContainer<SELF>` 를 바로 만들 수 없어 하위 타입으로 감싼다.
 */
class KMySQLContainer(
    image: String,
) : MySQLContainer<KMySQLContainer>(image)
