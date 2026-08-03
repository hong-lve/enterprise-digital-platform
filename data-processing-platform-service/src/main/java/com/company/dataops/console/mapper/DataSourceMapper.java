package com.company.dataops.console.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.dataops.console.entity.DataSourceEntity;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;

public interface DataSourceMapper extends BaseMapper<DataSourceEntity> {
    /**
     * Raw, un-decrypted password column values, for EncryptionKeyRotationService's
     * status report - a plain @Select bypasses DataSourceEntity's own
     * autoResultMap-driven typeHandler (which would silently hand back
     * plaintext here instead of the ciphertext this needs to inspect).
     */
    @Select("SELECT id, password FROM data_source")
    List<Map<String, Object>> selectRawIdAndPassword();
}
