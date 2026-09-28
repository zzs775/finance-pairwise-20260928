import { dedupeRecords } from "./dedupe";
import { readDataset, toCanonicalJson, writeReport } from "./io";
import { analyzeDataset } from "./pipeline";
import { generateSample } from "./sample";
import { normalizeDataset } from "./validate";

export interface CliArgs {
  input: string | null;
  output: string | null;
  sample: boolean;
  check: boolean;
}

export function parseArgs(argv: string[]): CliArgs {
  const args: CliArgs = {
    input: null,
    output: null,
    sample: false,
    check: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (token === undefined) continue;
    switch (token) {
      case "--input":
      case "-i":
        args.input = argv[++i] ?? null;
        break;
      case "--output":
      case "-o":
        args.output = argv[++i] ?? null;
        break;
      case "--sample":
      case "-s":
        args.sample = true;
        break;
      case "--check":
        args.check = true;
        break;
      case "--help":
      case "-h":
        return { ...args, help: true } as CliArgs;
      default:
        throw new Error(`unknown argument: ${token}`);
    }
  }
  return args;
}

export const HELP_TEXT = `
Implementation-shortfall analyzer

Usage:
  node dist/src/cli.js [--input FILE] [--output FILE] [--sample] [--check]

Options:
  -i, --input FILE    read a dataset JSON file (default: synthetic sample)
  -o, --output FILE   write the canonical JSON report (default: stdout)
  -s, --sample        force the built-in synthesized dataset
      --check         exit non-zero when report.status is "failed"
  -h, --help          show this help

Exit codes:
  0 analysis completed (with --check, also requires status "ok")
  1 report failed validation/reconciliation under --check
  2 bad CLI arguments or unreadable input
`;

export async function main(argv: string[]): Promise<number> {
  let args: CliArgs;
  try {
    args = parseArgs(argv);
  } catch (error) {
    process.stderr.write(`${(error as Error).message}\n${HELP_TEXT}`);
    return 2;
  }
  if ((args as CliArgs & { help?: boolean }).help) {
    process.stdout.write(HELP_TEXT);
    return 0;
  }

  try {
    let records;
    if (args.input && !args.sample) {
      records = readDataset(args.input).records;
    } else {
      records = generateSample(0).records;
    }
    const { unique, duplicates } = dedupeRecords(records);
    const dataset = normalizeDataset(unique);
    dataset.duplicates = duplicates;
    const report = analyzeDataset(dataset);
    const json = toCanonicalJson(report);
    if (args.output) {
      writeReport(args.output, report);
    } else {
      process.stdout.write(json);
    }
    if (args.check && report.status !== "ok") return 1;
    return 0;
  } catch (error) {
    process.stderr.write(`error: ${(error as Error).message}\n`);
    return 2;
  }
}

if (require.main === module) {
  void main(process.argv.slice(2)).then((code) => {
    process.exitCode = code;
  });
}
