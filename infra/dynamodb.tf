# A tabela de achados. O desenho é o mesmo que o código espera (AchadoItem): partição = ARN do recurso, ordenação = regra,
# e o índice status-severidade para "achados abertos e graves" sem varrer a tabela.
resource "aws_dynamodb_table" "achados" {
  name         = "${var.nome}-achados"
  billing_mode = "PAY_PER_REQUEST" # paga por requisição: uma execução por dia custa centavos
  hash_key     = "arn"
  range_key    = "regra"

  attribute {
    name = "arn"
    type = "S"
  }

  attribute {
    name = "regra"
    type = "S"
  }

  attribute {
    name = "status"
    type = "S"
  }

  attribute {
    name = "severidadeNivel"
    type = "N"
  }

  global_secondary_index {
    name            = "status-severidade"
    projection_type = "ALL"

    key_schema {
      attribute_name = "status"
      key_type       = "HASH"
    }

    key_schema {
      attribute_name = "severidadeNivel"
      key_type       = "RANGE"
    }
  }

  point_in_time_recovery {
    enabled = true
  }

  # Para o `terraform destroy` remover tudo, como o roteiro pede. Em produção de verdade, ligue a proteção.
  deletion_protection_enabled = false
}
