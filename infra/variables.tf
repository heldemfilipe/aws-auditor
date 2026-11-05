variable "regiao" {
  description = "Região onde o auditor roda (a tabela e a task ficam aqui)."
  type        = string
  default     = "us-east-1"
}

variable "nome" {
  description = "Prefixo dos recursos."
  type        = string
  default     = "aws-auditor"
}

variable "imagem" {
  description = "Imagem do auditor, com tag (ex.: ghcr.io/DONO/aws-auditor:1.0.0). Uma imagem privada exige credenciais no registro, que este módulo não configura."
  type        = string
}

variable "vpc_id" {
  description = "VPC onde a task roda (para o security group)."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets da task. Em subnet pública, a task precisa de IP público para chegar à AWS e ao registro; subnet privada exige NAT ou endpoints, que têm custo por hora."
  type        = list(string)
}

variable "ip_publico" {
  description = "Dá IP público à task (necessário em subnet pública, sem NAT)."
  type        = bool
  default     = true
}

variable "regioes_auditadas" {
  description = "Regiões que a varredura percorre (volumes e security groups; S3 e IAM são varridos uma vez)."
  type        = list(string)
  default     = ["us-east-1"]
}

variable "agendamento" {
  description = "Expressão do EventBridge Scheduler (UTC)."
  type        = string
  default     = "cron(0 9 * * ? *)"
}

variable "agendamento_ativo" {
  description = "O agendamento nasce DESLIGADO de propósito: ligue só depois de conferir o custo e de preencher os parâmetros do SSM."
  type        = bool
  default     = false
}

variable "cpu" {
  description = "CPU da task Fargate (unidades)."
  type        = number
  default     = 512
}

variable "memoria" {
  description = "Memória da task Fargate (MiB). A JVM com Spring precisa de folga; 512 MiB pode faltar."
  type        = number
  default     = 1024
}

variable "retencao_dos_logs_em_dias" {
  type    = number
  default = 30
}
