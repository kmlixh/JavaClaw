ALTER TABLE llm_provider_config
    ADD COLUMN IF NOT EXISTS model_mapping_json TEXT;

UPDATE llm_provider_config
SET model_mapping_json = jsonb_build_object(
        'models',
        jsonb_build_array(
                jsonb_build_object(
                        'displayName', model,
                        'apiModel', model
                )
        )
                         )::text
WHERE model_mapping_json IS NULL
   OR btrim(model_mapping_json) = '';

ALTER TABLE llm_provider_config
    ALTER COLUMN model_mapping_json SET DEFAULT '{"models":[]}',
    ALTER COLUMN model_mapping_json SET NOT NULL;
