const simpleSchema = require('./data/simple-schema.json');
const simpleSchema2 = require('./data/simpleSchema.json');
const largeSchema = require('./data/large-schema.json');
const largeSchema2 = require('./data/schema-10k.json');

const schemaWithName = {
  name: 'etlSchemaBody',
  schema: {
    name: 'etlSchemaBody',
    type: 'record',
    fields: [
      {
        name: 'new_field',
        type: 'string',
      },
    ],
  },
};
const schemaWithModifiedType = {
  name: 'etlSchemaBody',
  schema: {
    name: 'etlSchemaBody',
    type: 'record',
    fields: [
      {
        name: 'new_field',
        type: 'boolean',
      },
    ],
  },
};

const schemaWithMap = {
  name: 'etlSchemaBody',
  schema: {
    name: 'etlSchemaBody',
    type: 'record',
    fields: [
      {
        name: 'new_field',
        type: {
          type: 'map',
          keys: 'string',
          values: 'string',
        },
      },
    ],
  },
};

export {
  schemaWithMap,
  schemaWithName,
  schemaWithModifiedType,
  simpleSchema,
  simpleSchema2,
  largeSchema,
  largeSchema2,
};
