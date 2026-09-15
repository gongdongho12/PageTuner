import { pathToFileURL } from 'node:url';
import { generateContract } from './generate-contract.mjs';

export const generateWorkflowApi = (check = false) => generateContract('workflow-v1.openapi.json', 'workflow.ts', check);

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await generateWorkflowApi(process.argv.includes('--check'));
}
