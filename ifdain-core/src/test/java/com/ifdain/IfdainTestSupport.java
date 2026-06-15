package com.ifdain;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 测试基类：提供测试中通用的工具方法
 */
public abstract class IfdainTestSupport {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 测试用爱发电 Webhook 签名公钥（PEM 格式） */
    protected static final String TEST_WEBHOOK_PUBLIC_KEY = 
        "-----BEGIN PUBLIC KEY-----\n" +
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwzD+q8P9kz/VsIvR+aGd\n" +
        "GgC6XxEvtFs1EVylqoV6Pt+udxXGRvCjzgvfLqgM6kL/5ZXVU+l/MY10Ffz7S5kl\n" +
        "q8XOf59VBbsFMEgkl7xe8zzyon7JwFuMXlFUlTuJffazL2LmWFhM0S203XHNZrxf\n" +
        "M+crWJp1fyIWqXbE7lzxudhXH+sUb3oqvzh13cde/qovXaYw59XGkX2eUBMHfTBF\n" +
        "goMIaRa/DDMUHHPWE9ZIFkG/MWHKgLre5a+5fXAHiqfGFwaXKmYArGBCS8GcuIlI\n" +
        "v1z07ztI3ASytzDjkjtei75UzGZClSTgP465Gix5auhh/H7EBvWtkHTWR2QuULLe\n" +
        "PQIDAQAB\n" +
        "-----END PUBLIC KEY-----\n";

    /** 测试用爱发电 Webhook 签名私钥（PKCS8 PEM 格式） */
    protected static final String TEST_WEBHOOK_PRIVATE_KEY =
        "-----BEGIN PRIVATE KEY-----\n" +
        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDDMP6rw/2TP9Ww\n" +
        "i9H5oZ0aALpfES+0WzURXKWqhXo+3653FcZG8KPOC98uqAzqQv/lldVT6X8xjXQV\n" +
        "/PtLmSWrxc5/n1UFuwUwSCSXvF7zPPKifsnAW4xeUVSVO4l99rMvYuZYWEzRLbTd\n" +
        "cc1mvF8z5ytYmnV/IhapdsTuXPG52Fcf6xRveiq/OHXdx17+qi9dpjDn1caRfZ5Q\n" +
        "Ewd9MEWCgwhpFr8MMxQcc9YT1kgWQb8xYcqAut7lr7l9cAeKp8YXBpcqZgCsYEJL\n" +
        "wZy4iUi/XPTvO0jcBLK3MOOSO16LvlTMZkKVJOA/jrkaLHlq6GH8fsQG9a2QdNZH\n" +
        "ZC5Qst49AgMBAAECggEALM38z2nENb4z63wU3VPjL3pWTYcw55n+Eves0XklJ/SJ\n" +
        "ZC1734HSX/QqrVeG62kou0cK+mGBQFsjd/6jomVLl+PmdDYbOp8E4GHXAHuNYhEt\n" +
        "8u3emsiI7oeFvmG1vFPaKrjtff761xNCToiWaQw5YD0inoqKCP5GrGymZL6yAAIJ\n" +
        "CY+L+a8TSo+Z/q4Lq/VZg8RI83YwWeLT6XwIQarwh8MIl5QBiBSBJ1DZGZ1kNx7n\n" +
        "UJ4dljinuIOJYncKIU9RqC+x0X//fv5I7Veij12p8ZMyq2qnX1COUgmda2wBJmLk\n" +
        "0swaYNi/pE8mwly77T56xh6VW4j/qzH4hMe8C8xpgwKBgQD85+lEav/QRvcxUooM\n" +
        "yHSo21q28Xk3eDByt98+F04AD2/vBWEdnqheGXjEVKqtEu+8p+nftiJS16dZp6D1\n" +
        "OiiwBW7hw4jWSOxMGcXGRY5x1FSKdSvWef5MFuy6M1SHjg3giHpVkd9L4ceZBvXT\n" +
        "7YTM6Gi/HpouymgsFbL+fAcr6wKBgQDFlFMYXNkW1zJGSIxItR+1rdHtMN+LYRgu\n" +
        "iq7X5LGOTBY7nAJ2PrfUE2cFJWS8THNDSZqG4rAzv8QEfHgg4kJVv1eJRckPR7Fl\n" +
        "Q94tnKA765m9WWPdY2nDP+x/ZIKddz5Dr6alNYWSyGC48P3jhtxYbVPGgM0MXOfV\n" +
        "8Pzs5H9cdwKBgFEubE1+/0CvFvgpI5E442G0j+j7mEp69SpN5YY72tjgG7EhC+yD\n" +
        "Gk6iZIa5GtRVqMjpKYSbJWdsPBbmXR96nMbr54zmxEzsuZUwDLE5cKEPZFfRhtHg\n" +
        "9QUdsr2OborYyZGSnZpMKd5kyjpP5qcxrhhMXhtDSoDMjiXgjUVtMKwjAoGACsQJ\n" +
        "PxOYFKGHMMM1OaT85FZjUOxP0fVpbufJnSPt9NX6hCb9D1pdg+XGwEYIViZIRYtW\n" +
        "KzFg7oDtel0Z4pjRioCRkX5G9VkvtlfbXFrjdEBjMSkUwvRux9/M0Cg24cOkleWV\n" +
        "S/09mQDoHEutWd94VC0o2nEcOyc4zrSB45qTwgMCgYEA+9QskEWzJfj67/kVZIw8\n" +
        "veTGBK7fa9jvjUGRUbNn3bLJMYNVPoNrdrur37Fmt/R8p19tNHOTkPTCIWTAbdEs\n" +
        "oQPpL4z6ZQ0Cy90eAuwu11SCt6LTSRb8R71UGQ4F5tw9QqvW4SUlHPGhJXWEBWJs\n" +
        "8fo6mk28Bp7oy7MF66ywyCM=\n" +
        "-----END PRIVATE KEY-----\n";

    /** 测试用 API token */
    protected static final String TEST_TOKEN = "test_token_123";

    /** 测试用 API user_id */
    protected static final String TEST_USER_ID = "test_user_456";

    /** 构造用于 API 签名的参数 Map */
    protected static java.util.Map<String, Object> apiParams(long ts) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("ts", String.valueOf(ts));
        params.put("user_id", TEST_USER_ID);
        return params;
    }

    /** 生成一个测试用爱发电订单号 */
    protected static String mockOrderNo() {
        return "order_" + System.nanoTime();
    }
}
